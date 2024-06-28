package net.imadz.domain.entities.behaviors

import net.imadz.domain.entities.TransactionEntity._

object TransactionEventHandler {
  def apply: TransactionEventHandler = (state, event) => event match {
    case TransactionInitiated(fromUserId, toUserId, amount) =>
      if (state.status == New) {
        state.copy(
          fromUserId = Some(fromUserId),
          toUserId = Some(toUserId),
          amount = Some(amount),
          status = Initiated
        )
      } else {
        state
      }

    case TransactionPrepared(id) =>
      if (state.id == id && state.status == Initiated) {
        state.copy(status = Prepared)
      } else {
        state
      }

    case TransactionCompleted(id) =>
      if (state.id == id && state.status == Prepared) {
        state.copy(status = Completed)
      } else {
        state
      }

    case TransactionFailed(id, reason) =>
      if (state.id == id) {
        state.copy(status = Failed(reason))
      } else {
        state
      }
  }
}