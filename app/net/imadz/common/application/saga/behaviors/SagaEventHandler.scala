package net.imadz.common.application.saga.behaviors

import net.imadz.common.application.saga.TransactionCoordinator._

object SagaEventHandler {
  val eventHandler: (State, SagaEvent) => State = { (state, event) =>
    event match {
      case TransactionStarted(transaction) =>
        state.copy(currentTransaction = Some(transaction))
      case TransactionPhaseStarted(phase) =>
        state.copy(currentPhase = phase)
      case TransactionStepStarted(step) =>
        state.copy(currentStep = Some(step))
      case StepCompleted(step, _) =>
        state.copy(completedSteps = state.completedSteps + step, currentStep = None)
      case PhaseCompleted(phase, _) =>
        state.copy(currentPhase = phase)
      case TransactionCompleted(_) =>
        State() // Reset state for next transaction
    }
  }
}
