package net.imadz.infra.saga

import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import akka.persistence.typed.PersistenceId
import com.typesafe.config.ConfigFactory
import net.imadz.infra.saga.SagaParticipant.{NonRetryableFailure, RetryableFailure, SagaResult}
import net.imadz.infra.saga.SagaPhase._
import net.imadz.infra.saga.SagaTransactionCoordinator.TransactionResult
import net.imadz.infra.saga.StepExecutor.{CircuitBreakerSettings, StepResult}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

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
      val eventSourcedTestKit = createEventSourcedTestKit((_, _) => createSuccessfulStepExecutor())
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
        if (step.stepId == "prepare2") createFailingStepExecutor()
        else createSuccessfulStepExecutor()
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
        if (step.stepId == "commit1") createFailingStepExecutor()
        else createSuccessfulStepExecutor()
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
        if (step.stepId == "prepare1") createStepExecutor(retryingParticipant)
        else createSuccessfulStepExecutor()
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

  }

  private def createSuccessfulStepExecutor[E, R](): ActorRef[StepExecutor.Command] = {
    spawn(Behaviors.receiveMessage[StepExecutor.Command] {
      case StepExecutor.Start(transactionId, step, replyTo: Option[ActorRef[StepResult[E, R]]]) =>
        replyTo.foreach(_ ! StepExecutor.StepCompleted[E, R](transactionId, SagaResult.empty[R](), StepExecutor.State()))
        Behaviors.same
    })
  }

  private def createFailingStepExecutor(): ActorRef[StepExecutor.Command] = {
    spawn(Behaviors.receiveMessage[StepExecutor.Command] {
      case StepExecutor.Start(transactionId, step, replyTo) =>
        replyTo.foreach(_ ! StepExecutor.StepFailed(transactionId, Left(NonRetryableFailure("Test failure")), StepExecutor.State()))
        Behaviors.same
    })
  }

  private def createStepExecutor(participant: SagaParticipant[_, _], circuitBreakerSettings: CircuitBreakerSettings = CircuitBreakerSettings(5, 30.seconds, 30.seconds)): ActorRef[StepExecutor.Command] = {
    spawn(StepExecutor[Any, Any](
      PersistenceId.ofUniqueId(s"step-executor-${java.util.UUID.randomUUID()}"),
      defaultMaxRetries = 5,
      initialRetryDelay = 100.millis,
      circuitBreakerSettings = circuitBreakerSettings
    ))
  }


}