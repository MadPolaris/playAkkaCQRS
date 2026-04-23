package net.imadz.infra.saga.handlers

import net.imadz.infra.saga.SagaTransactionStep
import net.imadz.infra.saga.StepExecutor._

object StepExecutorEventHandler {
  def eventHandler[E, R, C]: (State[E, R, C], Event) => State[E, R, C] = { (state, event) =>
    event match {
      case ExecutionStarted(transactionId, step, replyTo) =>
        state.copy(transactionId = Some(transactionId),
          step = Some(step.asInstanceOf[SagaTransactionStep[E, R, C]]), status = Ongoing,
          replyTo = Some(replyTo))
      case OperationSucceeded(_) =>
        state.copy(status = Succeed)
      case OperationFailed(error) =>
        state.copy(status = Failed, lastError = Some(error))
      case RetryScheduled(_) =>
        state.copy(retries = state.retries + 1, status = Ongoing)

    }
  }


}
