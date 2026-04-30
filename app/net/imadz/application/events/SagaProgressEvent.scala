package net.imadz.application.events

import net.imadz.common.CborSerializable

sealed trait SagaProgressEvent extends CborSerializable {
  def traceId: String
}

object SagaProgressEvent {
  case class TransactionStarted(transactionId: String, steps: List[String], traceId: String) extends SagaProgressEvent
  case class StepOngoing(transactionId: String, stepId: String, phase: String, traceId: String) extends SagaProgressEvent
  case class StepCompleted(transactionId: String, stepId: String, phase: String, traceId: String, isManual: Boolean = false) extends SagaProgressEvent
  case class StepFailed(transactionId: String, stepId: String, phase: String, error: String, traceId: String) extends SagaProgressEvent
  case class TransactionCompleted(transactionId: String, traceId: String) extends SagaProgressEvent
  case class TransactionFailed(transactionId: String, reason: String, traceId: String) extends SagaProgressEvent
  case class TransactionSuspended(transactionId: String, reason: String, traceId: String) extends SagaProgressEvent
}
