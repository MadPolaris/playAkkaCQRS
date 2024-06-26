package net.imadz.domain.entities

import net.imadz.common.CommonTypes.Id
import net.imadz.domain.values.Money


object CreditBalanceEntity {

  // State
  case class CreditBalanceState(userId: Id, accountBalance: Map[String, Money], reservedAmount: Map[Id, Money])

  def empty(userId: Id): CreditBalanceState = CreditBalanceState(userId, Map.empty, Map.empty)

  // Event
  sealed trait CreditBalanceEvent
  case class BalanceChanged(update: Money, timestamp: Long = System.currentTimeMillis()) extends CreditBalanceEvent
  case class FundsReserved(transferId: Id, amount: Money) extends CreditBalanceEvent
  case class ReservationReleased(transferId: Id, amount: Money) extends CreditBalanceEvent
  case class TransferCompleted(transferId: Id) extends CreditBalanceEvent


  // Event Handler Extension Point
  type CreditBalanceEventHandler = (CreditBalanceState, CreditBalanceEvent) => CreditBalanceState

}