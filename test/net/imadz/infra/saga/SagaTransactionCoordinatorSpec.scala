package net.imadz.infra.saga

import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import akka.persistence.typed.PersistenceId
import com.typesafe.config.ConfigFactory
import net.imadz.infra.saga.SagaParticipant.{NonRetryableFailure, RetryableFailure}
import net.imadz.infra.saga.SagaPhase._
import net.imadz.infra.saga.SagaTransactionCoordinator.{TransactionResult, TransactionStarted}
import net.imadz.infra.saga.{AlwaysFailingParticipant, SagaTransactionCoordinator, SagaTransactionStep, StepExecutor, SuccessfulParticipant}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt

class SagaTransactionCoordinatorSpec extends ScalaTestWithActorTestKit(
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

  private def createSuccessfulStepExecutor(): ActorRef[StepExecutor.Command] = {
    spawn(Behaviors.receiveMessage[StepExecutor.Command] {
      case StepExecutor.Start(transactionId, step, replyTo) =>
        replyTo.foreach(_ ! StepExecutor.StepCompleted(step.stepId, Right(()), StepExecutor.State()))
        Behaviors.same
    })
  }

  private def createFailingStepExecutor(): ActorRef[StepExecutor.Command] = {
    spawn(Behaviors.receiveMessage[StepExecutor.Command] {
      case StepExecutor.Start(transactionId, step, replyTo) =>
        replyTo.foreach(_ ! StepExecutor.StepFailed(step.stepId, Left(NonRetryableFailure("Test failure")), StepExecutor.State()))
        Behaviors.same
    })
  }

  "SagaTransactionCoordinator" should {
    "successfully complete a transaction" in {
      val eventSourcedTestKit = createEventSourcedTestKit((_, _) => createSuccessfulStepExecutor())
      val transactionId = "test-transaction"
      val steps = List(
        SagaTransactionStep("step1", PreparePhase, SuccessfulParticipant, 2),
        SagaTransactionStep("step2", PreparePhase, SuccessfulParticipant, 2),
        SagaTransactionStep("step3", CommitPhase, SuccessfulParticipant, 2),
        SagaTransactionStep("step4", CommitPhase, SuccessfulParticipant, 2)
      )
      val prob = createTestProbe[TransactionResult]()
      val result = eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(prob.ref)))

      result.event shouldBe SagaTransactionCoordinator.TransactionStarted(transactionId, steps)
      result.state.status shouldBe SagaTransactionCoordinator.InProgress

      val expected = prob.receiveMessage(10.seconds)

      expected shouldBe TransactionResult(
        successful = true,
        SagaTransactionCoordinator.State(
          transactionId = Some(transactionId),
          steps = steps,
          currentPhase = CommitPhase,
          status = SagaTransactionCoordinator.Completed,
        ),
        stepTraces = List.fill(4)(StepExecutor.State())
      )
    }

    "handle failure during PreparePhase and initiate compensation" in {
      val eventSourcedTestKit = createEventSourcedTestKit((_, step) =>
        if (step.phase == PreparePhase) createFailingStepExecutor()
        else createSuccessfulStepExecutor()
      )
      val transactionId = "failed-transaction"
      val steps = List(
        SagaTransactionStep("step1", PreparePhase, AlwaysFailingParticipant, 2),
        SagaTransactionStep("step2", CompensatePhase, SuccessfulParticipant, 2)
      )
      val prob = createTestProbe[TransactionResult]()
      val result = eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(prob.ref)))

      result.event shouldBe SagaTransactionCoordinator.TransactionStarted(transactionId, steps)
      result.state.status shouldBe SagaTransactionCoordinator.InProgress

      val expected = prob.receiveMessage(10.seconds)

      expected shouldBe TransactionResult(
        successful = false,
        SagaTransactionCoordinator.State(
          transactionId = Some(transactionId),
          steps = steps,
          currentPhase = CompensatePhase,
          status = SagaTransactionCoordinator.Failed,
        ),
        stepTraces = List(StepExecutor.State(), StepExecutor.State())
      )
    }

    "handle non-retryable failure during CommitPhase" in {
      val eventSourcedTestKit = createEventSourcedTestKit((_, step) =>
        if (step.phase == CommitPhase) createFailingStepExecutor()
        else createSuccessfulStepExecutor()
      )
      val transactionId = "commit-failed-transaction"
      val steps = List(
        SagaTransactionStep("step1", PreparePhase, SuccessfulParticipant, 2),
        SagaTransactionStep("step2", CommitPhase, AlwaysFailingParticipant, 2)
      )
      val prob = createTestProbe[TransactionResult]()
      val result = eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(prob.ref)))

      result.event shouldBe SagaTransactionCoordinator.TransactionStarted(transactionId, steps)
      result.state.status shouldBe SagaTransactionCoordinator.InProgress

      val expected = prob.receiveMessage(10.seconds)

      expected shouldBe TransactionResult(
        successful = false,
        SagaTransactionCoordinator.State(
          transactionId = Some(transactionId),
          steps = steps,
          currentPhase = CompensatePhase,
          status = SagaTransactionCoordinator.Failed,
        ),
        stepTraces = List(StepExecutor.State(), StepExecutor.State())
      )
    }
  }
}