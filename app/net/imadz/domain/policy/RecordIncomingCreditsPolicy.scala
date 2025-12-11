package net.imadz.domain.policy

import net.imadz.common.CommonTypes.{DomainPolicy, Id, iMadzError}
import net.imadz.domain.entities.CreditBalanceEntity.{CreditBalanceEvent, CreditBalanceState, IncomingCreditsRecorded}
import net.imadz.domain.values.Money

object RecordIncomingCreditsPolicy extends DomainPolicy[CreditBalanceEvent, CreditBalanceState, (Id, Money)] {
  def apply(state: CreditBalanceState, param: (Id, Money)): Either[iMadzError, List[CreditBalanceEvent]] = {
    val (transferId, amount) = param
    state.incomingCredits.get(transferId) match {
      case Some(_) => Left(iMadzError("60007", "incoming credits already registered"))
      case None => Right(List(IncomingCreditsRecorded(transferId, amount)))
    }
  }
}