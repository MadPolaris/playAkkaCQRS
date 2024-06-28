package net.imadz.domain.entities

import net.imadz.common.CommonTypes.Id
import net.imadz.domain.values.Money

object TransactionEntity {

  // State
  case class TransactionState(
    id: Id,
    fromUserId: Option[Id],
    toUserId: Option[Id],
    amount: Option[Money],
    status: TransactionStatus
  )

  def empty(id: Id): TransactionState = TransactionState(id, None, None, None, New)

  // Status
  sealed trait TransactionStatus
  case object New extends TransactionStatus
  case object Initiated extends TransactionStatus
  case object Prepared extends TransactionStatus
  case object Completed extends TransactionStatus
  case class Failed(reason: String) extends TransactionStatus

  // Event
  sealed trait TransactionEvent
  case class TransactionInitiated(fromUserId: Id, toUserId: Id, amount: Money) extends TransactionEvent
  case class TransactionPrepared(id: Id) extends TransactionEvent
  case class TransactionCompleted(id: Id) extends TransactionEvent
  case class TransactionFailed(id: Id, reason: String) extends TransactionEvent

  // Event Handler
  type TransactionEventHandler = (TransactionState, TransactionEvent) => TransactionState
}
