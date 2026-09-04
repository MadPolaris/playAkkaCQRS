package net.imadz.infra.saga.acceptance

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.persistence.typed.PersistenceId
import com.typesafe.config.ConfigFactory
import net.imadz.infra.saga.{SagaPhase, SagaTransactionCoordinator, StepExecutor}
import net.imadz.infra.saga.dsl.SagaRunner
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

/** AC-MF: manual-fix recovery for transactions suspended by a non-retryable compensate failure.
  *
  * The manual-fix intent must be journaled in the coordinator's own event stream so that
  * recovery never depends on best-effort message delivery to (possibly still-recovering)
  * step executors, and never on the executor's terminal-reply cache alone.
  *
  * Scenario shape (mirrors the Showcase Step-C incident): step "out" fails prepare with a
  * non-retryable business error, then its compensate fails non-retryably too — the
  * transaction suspends. Fixing "out"/compensate and resuming must reach the terminal
  * Failed("transaction failed but compensated") state. */
class ManualFixRecoveryAcceptanceSpec extends ScalaTestWithActorTestKit(
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

  private val ec: ExecutionContext = system.executionContext
  private implicit val scheduler: akka.actor.typed.Scheduler = system.scheduler

  private val cluster = akka.cluster.typed.Cluster(system)
  cluster.manager ! akka.cluster.typed.Join(cluster.selfMember.address)

  private val classic = system.classicSystem.asInstanceOf[akka.actor.ExtendedActorSystem]

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

  /** Drives a transaction into the Suspended state: "out" fails prepare (business,
    * non-retryable) and its compensate fails non-retryable as well, so the compensate
    * phase suspends exactly like the Showcase Step-C incident. */
  private def suspendedTransaction(txId: String, definitionName: String): (SagaRunner[String, TransferArgs], CountingParticipant, CountingParticipant) = {
    val p1 = new CountingParticipant("mf-out")(ec, scheduler)
    val p2 = new CountingParticipant("mf-in")(ec, scheduler)
    p1.prepareScript = Script.BusinessError("60001")
    p1.compensateScript = Script.BusinessError("60002")
    val definition = registerDefinition(definitionName, steps = Seq(logicalStep("out", p1), logicalStep("in", p2)))
    val runner = new SagaRunner(definition, coordinatorRef, system)

    val first = Await.result(runner.run(txId, TransferArgs("a", "b", 5), s"trace-$txId"), 30.seconds)
    first.successful shouldBe false
    first.snapshot.status shouldBe SagaTransactionCoordinator.Suspended.toString
    (runner, p1, p2)
  }

  "AC-MF manual-fix recovery" should {
    "complete a suspended transaction after fixStep + resolveSuspended" in {
      val (runner, p1, p2) = suspendedTransaction("tx-mf-1", "mf-def-1")

      runner.admin.fixStep("tx-mf-1", "out", SagaPhase.CompensatePhase)
      val result = Await.result(runner.admin.resolveSuspended("tx-mf-1"), 30.seconds)

      result.successful shouldBe false
      result.snapshot.status shouldBe SagaTransactionCoordinator.Failed.toString
      result.failReason should include("transaction failed but compensated")
      // the manually-fixed step is skipped, the healthy step is answered from its cached
      // terminal success — neither participant is invoked again
      p1.compensateCalls.get() shouldBe 1
      p2.compensateCalls.get() shouldBe 1

      eventually(timeout(15.seconds), interval(200.millis)) {
        val snap = Await.result(runner.statusOf("tx-mf-1"), 15.seconds).get
        snap.status shouldBe SagaTransactionCoordinator.Failed.toString
        val out = snap.steps.find(s => s.stepId == "out" && s.phase == "compensate").get
        out.status shouldBe "Succeeded"
      }
    }

    "journal the manual fix durably: the status snapshot reflects the fixed step before resume" in {
      val (runner, _, _) = suspendedTransaction("tx-mf-2", "mf-def-2")

      runner.admin.fixStep("tx-mf-2", "out", SagaPhase.CompensatePhase)

      // the intent must be visible from the coordinator's own journaled state —
      // no resume, no executor round-trip involved
      eventually(timeout(15.seconds), interval(200.millis)) {
        val snap = Await.result(runner.statusOf("tx-mf-2"), 15.seconds).get
        val out = snap.steps.find(s => s.stepId == "out" && s.phase == "compensate").get
        out.status shouldBe "Succeeded"
      }
    }

    "round-trip the StepManuallyFixed event through the journal adapter" in {
      val adapter = new net.imadz.infra.saga.persistence.SagaTransactionCoordinatorEventAdapter(classic)
      val event = SagaTransactionCoordinator.StepManuallyFixed("out", SagaPhase.CompensatePhase)
      val po = adapter.toJournal(event)
      import akka.persistence.typed.EventSeq
      adapter.fromJournal(po, adapter.manifest(event)) shouldBe EventSeq.single(event)
    }
  }

  override protected def afterAll(): Unit = super.afterAll()
}
