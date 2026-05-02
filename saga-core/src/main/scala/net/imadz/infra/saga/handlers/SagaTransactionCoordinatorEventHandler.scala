package net.imadz.infra.saga.handlers

import net.imadz.infra.saga.SagaPhase.{CommitPhase, CompensatePhase, PreparePhase}
import net.imadz.infra.saga.SagaTransactionCoordinator._
import net.imadz.infra.saga.model.ExecutionPlan

/**
 * Handles state transitions for the SagaTransactionCoordinator by applying events.
 */
object SagaTransactionCoordinatorEventHandler {

  /**
   * The event handler function for the SagaTransactionCoordinator.
   *
   * @param state the current state of the coordinator
   * @param event the event to apply
   * @return the updated state
   */
  def eventHandler: (State, Event) => State = { (state, event) =>
    val plan = ExecutionPlan(state.steps)
    
    event match {
      case TransactionStarted(transactionId, steps, traceId, singleStep) =>
        val newPlan = ExecutionPlan(steps)
        state.copy(
          transactionId = Some(transactionId),
          steps = steps,
          status = InProgress,
          traceId = traceId,
          singleStep = singleStep,
          currentStepGroup = newPlan.firstGroupInPhase(PreparePhase)
        )

      case TransactionPaused(_, _) =>
        state.copy(isPaused = true)

      case TransactionResumed(_, _) =>
        state.copy(isPaused = false)

      case PhaseFailed(_, phase) =>
        phase match {
          case PreparePhase | CommitPhase =>
            state.copy(
              currentPhase = CompensatePhase, 
              status = Compensating, 
              currentStepGroup = plan.firstGroupInPhase(CompensatePhase)
            )
          case CompensatePhase => state
        }

      case StepGroupSucceeded(_, phase, group) =>
        plan.nextGroup(phase, group) match {
          case Some(nextGroup) => state.copy(currentStepGroup = nextGroup)
          case None => state // Should be followed by PhaseSucceeded
        }

      case PhaseSucceeded(_, phase) =>
        phase match {
          case PreparePhase =>
            state.copy(
              currentPhase = CommitPhase, 
              currentStepGroup = plan.firstGroupInPhase(CommitPhase)
            )
          case CommitPhase => state.copy(status = Completed)
          case CompensatePhase => state
        }

      case TransactionCompleted(_) =>
        state.copy(status = Completed)

      case TransactionFailed(_, _) =>
        state.copy(status = Failed)

      case TransactionSuspended(_, _) =>
        state.copy(status = Suspended)

      case TransactionResolved(_) =>
        val newStatus = if (state.currentPhase == CompensatePhase) Compensating else InProgress
        state.copy(status = newStatus)

      case TransactionRetried(_, phase) =>
        state.copy(
          currentPhase = phase, 
          status = InProgress, 
          currentStepGroup = plan.firstGroupInPhase(phase)
        )

      case StepGroupStarted(_, phase, group, stepIds) =>
        state.copy(
          currentPhase = phase, 
          currentStepGroup = group, 
          pendingSteps = stepIds, 
          phaseResults = Nil
        )

      case StepResultReceived(_, stepId, result) =>
        val updatedSuccessful = result match {
          case Right(_) if state.currentPhase != CompensatePhase => state.successfulSteps + stepId
          case _ => state.successfulSteps
        }
        state.copy(
          pendingSteps = state.pendingSteps - stepId,
          phaseResults = result :: state.phaseResults,
          successfulSteps = updatedSuccessful
        )
    }
  }
}
