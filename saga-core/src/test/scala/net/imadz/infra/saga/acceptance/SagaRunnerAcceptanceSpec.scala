package net.imadz.infra.saga.acceptance

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.persistence.typed.PersistenceId
import com.typesafe.config.ConfigFactory
import net.imadz.infra.saga.StepExecutor
import net.imadz.infra.saga.SagaTransactionCoordinator
import net.imadz.infra.saga.dsl.{SagaRunner, SagaStartRejectedException}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.Await
import scala.concurrent.duration._

/** G1 acceptance: SagaRunner completion bridge + full-stack E2E on a single-node cluster
  * (AC-1.12; real-stack form of AC-1.4 completion/compensation semantics). */
class SagaRunnerAcceptanceSpec extends ScalaTestWithActorTestKit(
  ConfigFactory.parseString(
    """
      |akka {
      |  loglevel = warning
      |  actor {
      |    provider = cluster
      |    allow-java-serialization = on
      |    warn-about-java-serializer-usage = off
      |  }
      |  remote.artery {
      |    canonical { hostname = "127.0.0.1", port = 0 }
      |  }
      |  cluster.sharding.number-of-shards = 10
      |  akka.actor.testkit.typed.single-expect-default = 15s
      |  akka.actor.testkit.typed.serialize-messages = off
      |  akka.actor.testkit.typed.serialization.verify = off
      |  akka.persistence.testkit.events.serialize = off
      |}
      |""".stripMargin
  ).withFallback(EventSourcedBehaviorTestKitCompat.config)
) with AnyWordSpecLike with BeforeAndAfterAll with Eventually {

  import TestSagaSupport._

  private val ec = system.executionContext
  private implicit val scheduler: akka.actor.typed.Scheduler = system.scheduler

  // Single-node cluster
  private val cluster = akka.cluster.typed.Cluster(system)
  cluster.manager ! akka.cluster.typed.Join(cluster.selfMember.address)

  private val classic = system.classicSystem.asInstanceOf[akka.actor.ExtendedActorSystem]

  // Executors spawn as coordinator children; the pid is deterministic per executor name.
  private def stepExecutorBehavior(name: String): akka.actor.typed.Behavior[StepExecutor.Command] =
    StepExecutor[String, String, Any](
      PersistenceId.ofUniqueId(s"exec-$name"),
      context = 0,
      defaultMaxRetries = 5,
      initialRetryDelay = 100.millis,
      circuitBreakerSettings = StepExecutor.CircuitBreakerSettings(5, 10.seconds, 1.second),
      extendedSystem = classic
    )

  private val sharding = ClusterSharding(system)
  sharding.init(Entity(SagaTransactionCoordinator.entityTypeKey) { entityContext =>
    SagaTransactionCoordinator(PersistenceId.ofUniqueId(entityContext.entityId), stepExecutorBehavior, 30.seconds)(ec, 5.seconds)
  })
  private val coordinatorRef: String => akka.cluster.sharding.typed.scaladsl.EntityRef[SagaTransactionCoordinator.Command] =
    txId => sharding.entityRefFor(SagaTransactionCoordinator.entityTypeKey, txId)

  override protected def afterAll(): Unit = {
    super.afterAll()
  }

  "AC-1.12 SagaRunner" should {
    "complete run() with the terminal TransactionResult through the bridge" in {
      val p1 = new CountingParticipant("r-out")(ec, scheduler)
      val p2 = new CountingParticipant("r-in")(ec, scheduler)
      val definition = registerDefinition("ac12-def", steps = Seq(logicalStep("transfer-out", p1), logicalStep("transfer-in", p2)))
      val runner = new SagaRunner(definition, coordinatorRef, system)

      val result = Await.result(runner.run("tx-ac12", TransferArgs("alice", "bob", 100), "trace-ac12"), 30.seconds)
      result.successful shouldBe true
      result.snapshot.status shouldBe SagaTransactionCoordinator.Completed.toString
      result.snapshot.transactionId shouldBe "tx-ac12"
      // one StepSpecSnapshot per (stepId, phase): 2 steps x 3 phases
      result.snapshot.steps.map(s => (s.stepId, s.phase)) should contain theSameElementsAs Seq(
        ("transfer-out", "prepare"), ("transfer-out", "commit"), ("transfer-out", "compensate"),
        ("transfer-in", "prepare"), ("transfer-in", "commit"), ("transfer-in", "compensate"))
      result.snapshot.steps.map(_.status) should contain only "Succeeded"
      p1.prepareCalls.get() shouldBe 1
      p1.commitCalls.get() shouldBe 1
      p2.prepareCalls.get() shouldBe 1
    }

    "answer AlreadyFinished for a completed transaction without re-execution" in {
      val p = new CountingParticipant("r-done")(ec, scheduler)
      val definition = registerDefinition("ac12-done", steps = Seq(logicalStep("s", p)))
      val runner = new SagaRunner(definition, coordinatorRef, system)

      Await.result(runner.run("tx-ac12-done", TransferArgs("a", "b", 1)), 30.seconds)
      val callsAfterFirst = p.prepareCalls.get()
      callsAfterFirst shouldBe 1

      // The entity stopped after completion; sharding recreates it and the replay answers.
      val second = Await.result(runner.run("tx-ac12-done", TransferArgs("a", "b", 1)), 30.seconds)
      second.successful shouldBe true
      second.snapshot.status shouldBe SagaTransactionCoordinator.Completed.toString
      p.prepareCalls.get() shouldBe callsAfterFirst
    }

    "fail run() with ConflictingArgs when the same txId is reused with different args" in {
      val definition = registerDefinition("ac12-conflict", steps = Seq(logicalStep("s", new CountingParticipant("c")(ec, scheduler))))
      val runner = new SagaRunner(definition, coordinatorRef, system)
      Await.result(runner.run("tx-ac12-conflict", TransferArgs("a", "b", 1)), 30.seconds)
      val ex = intercept[SagaStartRejectedException] {
        Await.result(runner.run("tx-ac12-conflict", TransferArgs("a", "b", 2)), 30.seconds)
      }
      ex.rejection shouldBe SagaTransactionCoordinator.ConflictingArgs
    }

    "fail run() with PreCheckFailed when the definition preCheck rejects" in {
      import net.imadz.infra.saga.dsl.{ArgsCodec, SagaDefinition, SagaStep, SagaRegistry, ResiliencePolicy}
      val p = new CountingParticipant("r-precheck")(ec, scheduler)
      val definition = SagaDefinition[String, Any, TransferArgs](
        name = "ac12-precheck", version = 1,
        argsCodec = ArgsCodec.playJson[TransferArgs],
        steps = _ => Seq(SagaStep("s", p)),
        preCheck = args => if (args.amount > 0) Right(args) else Left("401"),
        errorText = e => (e, s"amount must be positive, got ${e}")
      )
      SagaRegistry.register(definition)
      val runner = new SagaRunner(definition, coordinatorRef, system)
      val ex = intercept[SagaStartRejectedException] {
        Await.result(runner.run("tx-ac12-pc", TransferArgs("a", "b", -1)), 30.seconds)
      }
      ex.rejection shouldBe SagaTransactionCoordinator.PreCheckFailed("401", "amount must be positive, got 401")
    }

    "compensate both participants when a prepare business error is non-retryable" in {
      val p1 = new CountingParticipant("c-out")(ec, scheduler)
      val p2 = new CountingParticipant("c-in")(ec, scheduler)
      p1.prepareScript = Script.BusinessError("60003")
      val definition = registerDefinition("ac12-comp", steps = Seq(logicalStep("out", p1), logicalStep("in", p2)))
      val runner = new SagaRunner(definition, coordinatorRef, system)

      val result = Await.result(runner.run("tx-ac12-comp", TransferArgs("a", "b", 5), "trace"), 30.seconds)
      result.successful shouldBe false
      result.snapshot.status shouldBe SagaTransactionCoordinator.Failed.toString
      p1.prepareCalls.get() shouldBe 1
      p1.compensateCalls.get() shouldBe 1
      p2.compensateCalls.get() shouldBe 1
      result.failReason should include("transaction failed but compensated")
    }

    "expose statusOf for durable polling (None for unknown transactions)" in {
      val definition = registerDefinition("ac12-status", steps = Seq(logicalStep("s", new CountingParticipant("st")(ec, scheduler))))
      val runner = new SagaRunner(definition, coordinatorRef, system)
      val unknown = Await.result(runner.statusOf("tx-never-started"), 15.seconds)
      unknown shouldBe None

      Await.result(runner.run("tx-ac12-status", TransferArgs("a", "b", 1)), 30.seconds)
      // entity resurrection + replay after the terminal stop can transiently exceed a single
      // ask budget under load — poll instead of a fixed Await (was flakily timing out at 15s)
      eventually(timeout(30.seconds), interval(200.millis)) {
        val known = Await.result(runner.statusOf("tx-ac12-status"), 15.seconds)
        known.map(_.status) shouldBe Some(SagaTransactionCoordinator.Completed.toString)
      }
    }

    "support the singleStep debug mode and admin.proceed" in {
      val p = new CountingParticipant("r-debug")(ec, scheduler)
      val definition = registerDefinition("ac12-debug", steps = Seq(logicalStep("s", p)))
      val runner = new SagaRunner(definition, coordinatorRef, system)

      // singleStep: the transaction starts and pauses before the first group;
      // run()'s Future stays pending until the terminal result (bridge contract).
      val runFuture = runner.run("tx-ac12-debug", TransferArgs("a", "b", 1), singleStep = true)
      eventually(timeout(20.seconds), interval(200.millis)) {
        val st = Await.result(runner.statusOf("tx-ac12-debug"), 15.seconds).get
        st.status shouldBe SagaTransactionCoordinator.InProgress.toString
        st.isPaused shouldBe true
      }
      p.prepareCalls.get() shouldBe 0

      // proceed #1 advances exactly one group (prepare), then pauses again.
      val proceed1 = runner.admin.proceed("tx-ac12-debug")
      eventually(timeout(20.seconds), interval(200.millis)) {
        p.prepareCalls.get() shouldBe 1
        val st = Await.result(runner.statusOf("tx-ac12-debug"), 15.seconds).get
        st.isPaused shouldBe true
        st.currentPhase shouldBe "commit"
      }

      // proceed #2 drives the commit phase to the terminal result, which completes
      // every registered waiter (run + both proceeds) through the bridge.
      val result = Await.result(runner.admin.proceed("tx-ac12-debug"), 30.seconds)
      result.successful shouldBe true
      p.commitCalls.get() shouldBe 1
      Await.result(runFuture, 15.seconds).snapshot.status shouldBe SagaTransactionCoordinator.Completed.toString
      Await.result(proceed1, 15.seconds).successful shouldBe true
    }
  }
}

/** Local alias so the fallback config reads cleanly. */
private object EventSourcedBehaviorTestKitCompat {
  val config: com.typesafe.config.Config = akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit.config
}
