package net.imadz.domain.policy

import net.imadz.common.CommonTypes.{DomainPolicy, Id, iMadzError}
import net.imadz.domain.entities.CreditBalanceEntity.{CreditBalanceEvent, CreditBalanceState, IncomingCreditsCanceled}

object CancelIncomingCreditPolicy extends DomainPolicy[CreditBalanceEvent, CreditBalanceState, Id] {
  def apply(state: CreditBalanceState, transferId: Id): Either[iMadzError, List[CreditBalanceEvent]] = {
    state.incomingCredits.get(transferId) match {
      case Some(_) => Right(List(IncomingCreditsCanceled(transferId)))
      case None => Left(iMadzError("60009", "incoming credits cannot be found or already be canceled before"))
    }
  }
}