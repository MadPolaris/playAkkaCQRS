package net.imadz.infra.saga

import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.actor.typed.ActorRef
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import akka.persistence.typed.PersistenceId
import com.typesafe.config.ConfigFactory
import net.imadz.infra.saga.SagaParticipant.RetryableFailure
import net.imadz.infra.saga.SagaPhase._
import net.imadz.infra.saga.SagaTransactionCoordinator.TransactionResult
import net.imadz.infra.saga.StepExecutor.CircuitBreakerSettings
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class StepExecutorSagaCoordinatorIntegrationSpec extends ScalaTestWithActorTestKit(
  ConfigFactory.parseString(
    """
      |akka {
      |  actor {
      |    serializers {
      |      proto = "akka.remote.serialization.ProtobufSerializer"
      |      saga-transaction-step = "net.imadz.infra.saga.SagaTransactionStepSerializer"
      |    }
      |    serialization-bindings {
      |      "com.google.protobuf.Message" = proto
      |      "net.imadz.infra.saga.SagaTransactionStep" = saga-transaction-step
      |    }
      |    allow-java-serialization = on
      |    warn-about-java-serializer-usage = off
      |  }
      |}
      |# In-memory database configuration for unit testing
      |database {
      |  url = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1"
      |  driver = org.h2.Driver
      |  connectionPool = disabled
      |  keepAliveConnection = true
      |}
      |akka.test.single-expect-default = 100s
      |akka.actor.testkit.typed.single-expect-default = 100s
      |""".stripMargin
  ).withFallback(EventSourcedBehaviorTestKit.config)
) with AnyWordSpecLike with BeforeAndAfterEach with LogCapturing {

  private def createEventSourcedTestKit(stepExecutorCreator: (String, SagaTransactionStep[_, _]) => ActorRef[StepExecutor.Command]) = {
    EventSourcedBehaviorTestKit[
      SagaTransactionCoordinator.Command,
      SagaTransactionCoordinator.Event,
      SagaTransactionCoordinator.State
    ](
      system,
      SagaTransactionCoordinator(
        PersistenceId.ofUniqueId("test-saga-coordinator"),
        stepExecutorCreator
      )
    )
  }

  "StepExecutor and SagaTransactionCoordinator Integration" should {

    "successfully complete a transaction with multiple steps across different phases" in {
      val eventSourcedTestKit = createEventSourcedTestKit((_, _) => createStepExecutor())
      val transactionId = "multi-phase-transaction"
      val steps = List(
        SagaTransactionStep("prepare1", PreparePhase, SuccessfulParticipant, 2),
        SagaTransactionStep("prepare2", PreparePhase, SuccessfulParticipant, 2),
        SagaTransactionStep("commit1", CommitPhase, SuccessfulParticipant, 2),
        SagaTransactionStep("commit2", CommitPhase, SuccessfulParticipant, 2)
      )
      val probe = createTestProbe[TransactionResult]()

      eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(probe.ref)))

      val result = probe.receiveMessage(10.seconds)

      result.successful shouldBe true
      result.state.status shouldBe SagaTransactionCoordinator.Completed
      result.state.currentPhase shouldBe CommitPhase
      result.stepTraces should have length 4
    }

    "handle failure in Prepare phase and initiate compensation" in {
      val eventSourcedTestKit = createEventSourcedTestKit((_, step) =>
        createStepExecutor()
      )
      val transactionId = "prepare-fail-transaction"
      val steps = List(
        SagaTransactionStep("prepare1", PreparePhase, SuccessfulParticipant, 2),
        SagaTransactionStep("prepare2", PreparePhase, AlwaysFailingParticipant, 2),
        SagaTransactionStep("commit1", CommitPhase, SuccessfulParticipant, 2),
        SagaTransactionStep("compensate1", CompensatePhase, SuccessfulParticipant, 2),
        SagaTransactionStep("compensate2", CompensatePhase, SuccessfulParticipant, 2)
      )
      val probe = createTestProbe[TransactionResult]()

      eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(probe.ref)))

      val result = probe.receiveMessage(10.seconds)

      result.successful shouldBe false
      result.state.status shouldBe SagaTransactionCoordinator.Failed
      result.state.currentPhase shouldBe CompensatePhase
      result.stepTraces should have length 4 // prepare1, prepare2 (failed), compensate1, compensate2
    }

    "handle failure in Commit phase and compensate all steps" in {
      val eventSourcedTestKit = createEventSourcedTestKit((_, step) =>
        createStepExecutor()
      )
      val transactionId = "commit-fail-transaction"
      val steps = List(
        SagaTransactionStep("prepare1", PreparePhase, SuccessfulParticipant, 2),
        SagaTransactionStep("prepare2", PreparePhase, SuccessfulParticipant, 2),
        SagaTransactionStep("commit1", CommitPhase, AlwaysFailingParticipant, 2),
        SagaTransactionStep("commit2", CommitPhase, SuccessfulParticipant, 2),
        SagaTransactionStep("compensate1", CompensatePhase, SuccessfulParticipant, 2),
        SagaTransactionStep("compensate2", CompensatePhase, SuccessfulParticipant, 2)
      )
      val probe = createTestProbe[TransactionResult]()

      eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(probe.ref)))

      val result = probe.receiveMessage(10.seconds)

      result.successful shouldBe false
      result.state.status shouldBe SagaTransactionCoordinator.Failed
      result.state.currentPhase shouldBe CompensatePhase
      result.stepTraces should have length 6 // prepare1, prepare2, commit1 (failed), compensate1, compensate2
    }

    "retry a step with temporary failure" in {
      val retryingParticipant = new RetryingParticipant()
      val eventSourcedTestKit = createEventSourcedTestKit((_, step) =>
        createStepExecutor()
      )
      val transactionId = "retry-transaction"
      val steps = List(
        SagaTransactionStep("prepare1", PreparePhase, retryingParticipant, 5),
        SagaTransactionStep("commit1", CommitPhase, SuccessfulParticipant, 2)
      )
      val probe = createTestProbe[TransactionResult]()

      eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(probe.ref)))

      val result = probe.receiveMessage(10.seconds)

      println(result)

      result.successful shouldBe true
      result.state.status shouldBe SagaTransactionCoordinator.Completed
      result.stepTraces.reverse.head.retries should be > 0
    }

    // Add more test cases here...

    "handle circuit breaker behavior" in {
      val circuitBreakerParticipant = new CircuitBreakerParticipant()
      val eventSourcedTestKit = createEventSourcedTestKit((_, step) =>
        createStepExecutor()
      )
      val transactionId = "circuit-breaker-transaction"
      val steps = List(
        SagaTransactionStep("circuit-breaker-step", PreparePhase, circuitBreakerParticipant, 10, 1.seconds))
      val probe = createTestProbe[TransactionResult]()

      eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(probe.ref)))

      val result = probe.receiveMessage(15.seconds)

      result.successful shouldBe false
      result.state.status shouldBe SagaTransactionCoordinator.Failed
      result.stepTraces.reverse.head.retries should be >= 3
    }


    "handle timeout in a step" in {
      val timeoutParticipant = new TimeoutParticipant()
      val eventSourcedTestKit = createEventSourcedTestKit((_, step) =>
        createStepExecutor()
      )
      val transactionId = "timeout-transaction"
      val steps = List(
        SagaTransactionStep("timeout-step", PreparePhase, timeoutParticipant, 2, timeoutDuration = 500.millis),
        SagaTransactionStep("compensate-step", CompensatePhase, SuccessfulParticipant, 2)
      )
      val probe = createTestProbe[TransactionResult]()

      eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(probe.ref)))

      val result = probe.receiveMessage(10.seconds)

      result.successful shouldBe false
      result.state.status shouldBe SagaTransactionCoordinator.Failed
      result.state.currentPhase shouldBe CompensatePhase
      result.stepTraces.reverse.head.lastError.get shouldBe a[RetryableFailure]
    }

    "handle partial compensation" in {
      val eventSourcedTestKit = createEventSourcedTestKit((_, step) => createStepExecutor())
      val transactionId = "partial-compensate-transaction"
      val steps = List(
        SagaTransactionStep("prepare1", PreparePhase, SuccessfulParticipant, 2),
        SagaTransactionStep("prepare2", PreparePhase, SuccessfulParticipant, 2),
        SagaTransactionStep("commit1", CommitPhase, SuccessfulParticipant, 2),
        SagaTransactionStep("commit2", CommitPhase, AlwaysFailingParticipant, 2),
        SagaTransactionStep("compensate1", CompensatePhase, AlwaysFailingParticipant, 2),
        SagaTransactionStep("compensate2", CompensatePhase, SuccessfulParticipant, 2)
      )
      val probe = createTestProbe[TransactionResult]()

      eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(probe.ref)))

      val result = probe.receiveMessage(10.seconds)

      result.successful shouldBe false
      result.state.status shouldBe SagaTransactionCoordinator.Failed
      result.state.currentPhase shouldBe CompensatePhase
      result.stepTraces should have length 6 // prepare1, prepare2, commit1, commit2 (failed), compensate1 (failed), compensate2
      result.stepTraces.count(_.status == StepExecutor.Failed) shouldBe 2
    }
  }

  private def createStepExecutor(circuitBreakerSettings: CircuitBreakerSettings = CircuitBreakerSettings(5, 30.seconds, 30.seconds)) = {
    spawn(StepExecutor[Any, Any](
      PersistenceId.ofUniqueId(s"step-executor-${java.util.UUID.randomUUID()}"),
      defaultMaxRetries = 5,
      initialRetryDelay = 100.millis,
      circuitBreakerSettings = circuitBreakerSettings
    ))
  }


}
