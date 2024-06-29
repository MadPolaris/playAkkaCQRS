package net.imadz.common.application.saga.behaviors

import net.imadz.common.application.saga.TransactionCoordinator._

// SagaEventHandler.scala
object SagaEventHandler {
  val eventHandler: (State, SagaEvent) => State = { (state, event) =>
    event match {
      case TransactionStarted(transaction) =>
        state.copy(currentTransaction = Some(transaction))

      case TransactionPhaseStarted(phase) =>
        state.copy(currentPhase = phase)

      case TransactionStepStarted(step) =>
        state.copy(currentStep = Some(step.copy(status = StepOngoing)))

      case StepCompleted(step, success) =>
        if (success) {
          state.copy(
            completedSteps = state.completedSteps + step.copy(status = StepCompleted),
            currentStep = None
          )
        } else {
          state.copy(
            failedSteps = state.failedSteps + step.copy(status = StepFailed),
            currentStep = None
          )
        }

      case StepFailed(step, reason) =>
        state.copy(
          failedSteps = state.failedSteps + step.copy(status = StepFailed, failedReason = Some(reason)),
          currentStep = None
        )

      case StepTimedOut(step) =>
        state.copy(
          failedSteps = state.failedSteps + step.copy(status = StepTimedOut),
          currentStep = None
        )

      case StepCompensated(step) =>
        state.copy(
          compensatedSteps = state.compensatedSteps + step.copy(status = StepCompensated),
          currentStep = None
        )

      case PhaseCompleted(phase, success) =>
        state.copy(currentPhase = "")

      case TransactionCompleted(success) =>
        if (success) {
          state.copy(
            currentTransaction = None,
            currentPhase = "",
            currentStep = None
          )
        } else {
          state.copy(
            currentTransaction = None,
            currentPhase = "",
            currentStep = None
          )
        }
    }
  }
}