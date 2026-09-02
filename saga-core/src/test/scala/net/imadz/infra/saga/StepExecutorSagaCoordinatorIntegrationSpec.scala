package net.imadz.infra.saga

import akka.actor.ExtendedActorSystem
import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.actor.typed.ActorRef
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import akka.persistence.typed.PersistenceId
import com.typesafe.config.ConfigFactory
import net.imadz.infra.saga.SagaParticipant.RetryableFailure
import net.imadz.infra.saga.SagaPhase._
import net.imadz.infra.saga.SagaTransactionCoordinator.{SagaStartReply, TransactionResult}
import net.imadz.infra.saga.StepExecutor.CircuitBreakerSettings
import net.imadz.infra.saga.acceptance.TestSagaSupport
import net.imadz.infra.saga.dsl.{SagaDefinition, SagaStep}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class StepExecutorSagaCoordinatorIntegrationSpec extends ScalaTestWithActorTestKit(
  ConfigFactory.parseString(
    """
      |akka {
      |  actor {
      |    allow-java-serialization = on
      |    warn-about-java-serializer-usage = off
      |  }
      |akka.test.single-expect-default = 100s
      |akka.actor.testkit.typed.single-expect-default = 100s
      |akka.actor.testkit.typed.serialize-messages = off
      |akka.actor.testkit.typed.serialize-creators = off
      |akka.actor.testkit.typed.serialization.verify = off
      |akka.persistence.testkit.events.serialize = off
      |}
      |""".stripMargin
  ).withFallback(EventSourcedBehaviorTestKit.config)
) with AnyWordSpecLike with BeforeAndAfterEach with BeforeAndAfterAll {

  import TestSagaSupport._

  private val ec = system.executionContext
  private implicit val scheduler: akka.actor.typed.Scheduler = system.scheduler

  private def createEventSourcedTestKit(stepExecutorBehavior: String => akka.actor.typed.Behavior[StepExecutor.Command],
                                        persistenceId: String = s"test-saga-coordinator-${java.util.UUID.randomUUID()}") = {
    EventSourcedBehaviorTestKit[
      SagaTransactionCoordinator.Command,
      SagaTransactionCoordinator.Event,
      SagaTransactionCoordinator.State
    ](
      system,
      SagaTransactionCoordinator(
        PersistenceId.ofUniqueId(persistenceId),
        stepExecutorBehavior
      )
    )
  }

  private def run(kit: EventSourcedBehaviorTestKit[SagaTransactionCoordinator.Command, SagaTransactionCoordinator.Event, SagaTransactionCoordinator.State],
                  definition: SagaDefinition[String, Any, TransferArgs],
                  transactionId: String): TransactionResult = {
    val startProbe = createTestProbe[SagaStartReply]()
    val completionProbe = createTestProbe[TransactionResult]()
    kit.runCommand(SagaTransactionCoordinator.StartSaga(transactionId, definition.name, definition.version,
      definition.argsCodec.encode(TransferArgs("a", "b", 10)), "test-trace-id", singleStep = false, Some(startProbe.ref), Some(completionProbe.ref)))
    completionProbe.receiveMessage(30.seconds)
  }

  "StepExecutor and SagaTransactionCoordinator Integration" should {

    "successfully complete a transaction with multiple steps across different phases" in {
      val eventSourcedTestKit = createEventSourcedTestKit(name => stepExecutorBehavior(system.classicSystem.asInstanceOf[ExtendedActorSystem])(name))
      val definition = registerDefinition("int-ok", steps = Seq(
        logicalStep("p1", AlwaysOkRawParticipant), logicalStep("p2", AlwaysOkRawParticipant)))

      val result = run(eventSourcedTestKit, definition, "multi-phase-transaction")

      result.successful shouldBe true
      result.snapshot.status shouldBe SagaTransactionCoordinator.Completed.toString
      result.snapshot.currentPhase shouldBe CommitPhase.toString
    }

    "handle failure in Prepare phase and initiate compensation" in {
      val eventSourcedTestKit = createEventSourcedTestKit(name => stepExecutorBehavior(system.classicSystem.asInstanceOf[ExtendedActorSystem])(name))
      // Fails prepare only; its compensate succeeds, so the transaction reaches Failed.
      val failingPrepare = new CountingParticipant("pf")(ec, scheduler)
      failingPrepare.prepareScript = Script.BusinessError("60003")
      val definition = registerDefinition("int-pfail", steps = Seq(
        logicalStep("p1", AlwaysOkRawParticipant), logicalStep("p2", failingPrepare)))

      val result = run(eventSourcedTestKit, definition, "prepare-fail-transaction")

      result.successful shouldBe false
      result.snapshot.status shouldBe SagaTransactionCoordinator.Failed.toString
      result.snapshot.currentPhase shouldBe CompensatePhase.toString
    }

    "handle failure in Commit phase and compensate all steps" in {
      val eventSourcedTestKit = createEventSourcedTestKit(name => stepExecutorBehavior(system.classicSystem.asInstanceOf[ExtendedActorSystem])(name))
      // Fails commit only; its compensate succeeds, so the transaction reaches Failed.
      val failingCommit = new CountingParticipant("cf")(ec, scheduler)
      failingCommit.commitScript = Script.BusinessError("60003")
      val definition = registerDefinition("int-cfail", steps = Seq(
        logicalStep("p1", AlwaysOkRawParticipant), logicalStep("p2", AlwaysOkRawParticipant),
        logicalStep("c1", failingCommit)))

      val result = run(eventSourcedTestKit, definition, "commit-fail-transaction")

      result.successful shouldBe false
      result.snapshot.status shouldBe SagaTransactionCoordinator.Failed.toString
      result.snapshot.currentPhase shouldBe CompensatePhase.toString
    }

    "retry a step with temporary failure" in {
      val retryingParticipant = new RetryingParticipant()
      val eventSourcedTestKit = createEventSourcedTestKit(name => stepExecutorBehavior(system.classicSystem.asInstanceOf[ExtendedActorSystem])(name))
      val definition = registerDefinition("int-retry", steps = Seq(
        logicalStep("p1", retryingParticipant, maxRetries = 5)))

      val result = run(eventSourcedTestKit, definition, "retry-transaction")

      result.successful shouldBe true
      result.snapshot.status shouldBe SagaTransactionCoordinator.Completed.toString
    }

    "handle circuit breaker behavior" in {
      val circuitBreakerParticipant = new CircuitBreakerParticipant()
      val eventSourcedTestKit = createEventSourcedTestKit(name => stepExecutorBehavior(system.classicSystem.asInstanceOf[ExtendedActorSystem])(name))
      val definition = registerDefinition("int-cb", steps = Seq(
        SagaStep("circuit-breaker-step", circuitBreakerParticipant, net.imadz.infra.saga.dsl.ResiliencePolicy(maxRetries = 10, timeoutPerAttempt = 1.second))))

      val result = run(eventSourcedTestKit, definition, "circuit-breaker-transaction")

      result.successful shouldBe false
      result.snapshot.status shouldBe SagaTransactionCoordinator.Failed.toString
    }


    "handle timeout in a step" in {
      val timeoutParticipant = new TimeoutParticipant()
      val eventSourcedTestKit = createEventSourcedTestKit(name => stepExecutorBehavior(system.classicSystem.asInstanceOf[ExtendedActorSystem])(name))
      val definition = registerDefinition("int-timeout", steps = Seq(
        SagaStep("timeout-step", timeoutParticipant, net.imadz.infra.saga.dsl.ResiliencePolicy(maxRetries = 2, timeoutPerAttempt = 500.millis)),
        logicalStep("compensate-step", AlwaysOkRawParticipant)))

      val result = run(eventSourcedTestKit, definition, "timeout-transaction")

      result.successful shouldBe false
      result.snapshot.status shouldBe SagaTransactionCoordinator.Failed.toString
      result.snapshot.currentPhase shouldBe CompensatePhase.toString
    }

    "handle partial compensation" in {
      val eventSourcedTestKit = createEventSourcedTestKit(name => stepExecutorBehavior(system.classicSystem.asInstanceOf[ExtendedActorSystem])(name))
      val definition = registerDefinition("int-partial", steps = Seq(
        logicalStep("p1", AlwaysOkRawParticipant), logicalStep("p2", AlwaysOkRawParticipant),
        logicalStep("c1", AlwaysOkRawParticipant), logicalStep("c2", AlwaysFailingParticipant),
        logicalStep("x1", AlwaysFailingParticipant), logicalStep("x2", AlwaysOkRawParticipant)))

      val result = run(eventSourcedTestKit, definition, "partial-compensate-transaction")

      result.successful shouldBe false
      result.snapshot.status shouldBe SagaTransactionCoordinator.Suspended.toString
      result.snapshot.currentPhase shouldBe CompensatePhase.toString
    }
  }

  private def stepExecutorBehavior(extendedActorSystem: ExtendedActorSystem, circuitBreakerSettings: CircuitBreakerSettings = CircuitBreakerSettings(5, 30.seconds, 30.seconds))(
      name: String): akka.actor.typed.Behavior[StepExecutor.Command] =
    StepExecutor[Any, Any, Any](
      PersistenceId.ofUniqueId(s"step-executor-$name-${java.util.UUID.randomUUID()}"),
      defaultMaxRetries = 5,
      initialRetryDelay = 100.millis,
      circuitBreakerSettings = circuitBreakerSettings,
      context = 0,
      extendedSystem = extendedActorSystem
    )

}
