package net.imadz.infra.saga.handlers

import net.imadz.infra.saga.SagaParticipant.SagaResult
import net.imadz.infra.saga.SagaTransactionStep
import net.imadz.infra.saga.StepExecutor._

object StepExecutorEventHandler {
  def eventHandler[E, R, C]: (State[E, R, C], Event) => State[E, R, C] = { (state, event) =>
    event match {
      case ExecutionStarted(transactionId, step, replyTo, traceId) =>
        state.copy(transactionId = Some(transactionId),
          step = Some(step.asInstanceOf[SagaTransactionStep[E, R, C]]), status = Ongoing,
          replyTo = Some(replyTo), traceId = Some(traceId))
      case OperationSucceeded(_, _, _, _, result) =>
        state.copy(status = Succeed, result = Some(result.asInstanceOf[SagaResult[R]]))
      case ManualFixCompleted(_, _, _, _, result) =>
        state.copy(status = Succeed, result = Some(result.asInstanceOf[SagaResult[R]]))
      case OperationFailed(_, _, _, _, error) =>
        val newStatus = if (error.isInstanceOf[net.imadz.infra.saga.SagaParticipant.RetryableFailure]) Ongoing else Failed
        state.copy(status = newStatus, lastError = Some(error))
      case RetryScheduled(_, _, _, _, _) =>
        state.copy(retries = state.retries + 1, status = Ongoing)

    }
  }


}
