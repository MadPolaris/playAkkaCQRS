package net.imadz.infra.saga

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.PersistenceId
import net.imadz.infra.saga.SagaPhase.PreparePhase
import net.imadz.infra.saga.SagaTransactionCoordinator._
import net.imadz.infra.saga.SagaParticipant.{NonRetryableFailure, SagaResult}
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

class SagaCoreEdgeCaseSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  private def createEventSourcedTestKit(stepExecutorCreator: (String, SagaTransactionStep[_, _, _]) => ActorRef[StepExecutor.Command],
                                        persistenceId: String = "test-saga-edge-cases",
                                        globalTimeout: FiniteDuration = 30.seconds) = {
    import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
    EventSourcedBehaviorTestKit[
      SagaTransactionCoordinator.Command,
      SagaTransactionCoordinator.Event,
      SagaTransactionCoordinator.State
    ](
      system,
      SagaTransactionCoordinator(
        PersistenceId.ofUniqueId(persistenceId),
        stepExecutorCreator,
        globalTimeout = globalTimeout
      )(system.executionContext)
    )
  }

  private def createSuccessfulStepExecutor(): ActorRef[StepExecutor.Command] = {
    spawn(Behaviors.receiveMessage[StepExecutor.Command] {
      case StepExecutor.Start(transactionId, step, replyTo, _) =>
        replyTo.foreach(_ ! StepExecutor.StepCompleted(transactionId, step.stepId, SagaResult.empty()))
        Behaviors.stopped
      case _ => Behaviors.same
    })
  }

  "SagaTransactionCoordinator Edge Cases" should {

    "ignore TransactionTimeout if transaction is already Completed" in {
      val transactionId = "timeout-on-completed"
      val steps = List(SagaTransactionStep[String, String, Any]("step1", PreparePhase, SuccessfulParticipant, 2))
      val eventSourcedTestKit = createEventSourcedTestKit((_, _) => createSuccessfulStepExecutor(), persistenceId = "test-edge-1")
      
      val prob = createTestProbe[TransactionResult]()
      eventSourcedTestKit.runCommand(StartTransaction(transactionId, steps, Some(prob.ref), "trace-1"))
      
      prob.receiveMessage(10.seconds).successful shouldBe true
      eventSourcedTestKit.getState().status shouldBe Completed
      
      // Send timeout now
      eventSourcedTestKit.runCommand(TransactionTimeout)
      
      // Status should remain Completed
      eventSourcedTestKit.getState().status shouldBe Completed
    }

    "ignore ProceedNextGroup if not paused" in {
      val transactionId = "proceed-not-paused"
      val steps = List(SagaTransactionStep[String, String, Any]("step1", PreparePhase, SuccessfulParticipant, 2))
      val eventSourcedTestKit = createEventSourcedTestKit((_, _) => createSuccessfulStepExecutor(), persistenceId = "test-edge-2")
      
      eventSourcedTestKit.runCommand(StartTransaction(transactionId, steps, None, "trace-2"))
      
      // Try to proceed next group while it's already running (or completed)
      eventSourcedTestKit.runCommand(ProceedNextGroup(None))
      
      // Should not crash or change state unexpectedly
      eventually(timeout(10.seconds)) {
         eventSourcedTestKit.getState().status should (be(InProgress) or be(Completed))
      }
    }

    "ignore InternalStepResult for mismatched transactionId" in {
       val transactionId = "mismatched-id"
       val steps = List(SagaTransactionStep[String, String, Any]("step1", PreparePhase, SuccessfulParticipant, 2))
       val eventSourcedTestKit = createEventSourcedTestKit((_, _) => createSuccessfulStepExecutor(), persistenceId = "test-edge-3")
       
       eventSourcedTestKit.runCommand(StartTransaction(transactionId, steps, None, "trace-3"))
       
       // Send a result for a DIFFERENT transactionId
       eventSourcedTestKit.runCommand(InternalStepResult(StepExecutor.StepCompleted("wrong-id", "step1", SagaResult.empty()), None))
       
       // Status should not change based on wrong ID
       eventSourcedTestKit.getState().transactionId shouldBe Some(transactionId)
    }

    "handle ManualFixStep by forwarding to child" in {
       val transactionId = "manual-fix"
       val steps = List(SagaTransactionStep[String, String, Any]("step1", PreparePhase, SuccessfulParticipant, 2))
       
       val childProbe = createTestProbe[StepExecutor.Command]()
       val eventSourcedTestKit = createEventSourcedTestKit((_, _) => childProbe.ref, persistenceId = "test-edge-4")
       
       eventSourcedTestKit.runCommand(StartTransaction(transactionId, steps, None, "trace-4"))
       
       // Wait for child to be started
       childProbe.expectMessageType[StepExecutor.Start[String, String, Any]]
       
       // Send manual fix
       eventSourcedTestKit.runCommand(ManualFixStep("step1", PreparePhase, None))
       
       // Child should receive ManualFix
       childProbe.expectMessageType[StepExecutor.ManualFix[String, String, Any]]
    }
  }
}
