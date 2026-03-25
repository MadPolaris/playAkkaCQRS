package net.imadz.domain.invariants

import net.imadz.common.CommonTypes.{InvariantRule, Id, iMadzError}
import net.imadz.domain.entities.CreditBalanceEntity.{CreditBalanceEvent, CreditBalanceState, IncomingCreditsRecorded}
import net.imadz.domain.values.Money

object RecordIncomingCreditsRule extends InvariantRule[CreditBalanceEvent, CreditBalanceState, (Id, Money)] {
  def apply(state: CreditBalanceState, param: (Id, Money)): Either[iMadzError, List[CreditBalanceEvent]] = {
    val (transferId, amount) = param
    state.incomingCredits.get(transferId) match {
      case Some(_) => Left(iMadzError("60007", "incoming credits already registered"))
      case None => Right(List(IncomingCreditsRecorded(transferId, amount)))
    }
  }
}
