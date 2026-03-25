package net.imadz.domain.invariants

import net.imadz.common.CommonTypes.{InvariantRule, Id, iMadzError}
import net.imadz.domain.entities.CreditBalanceEntity.{CreditBalanceEvent, CreditBalanceState, IncomingCreditsCanceled}

object CancelIncomingCreditRule extends InvariantRule[CreditBalanceEvent, CreditBalanceState, Id] {
  def apply(state: CreditBalanceState, transferId: Id): Either[iMadzError, List[CreditBalanceEvent]] = {
    state.incomingCredits.get(transferId) match {
      case Some(_) => Right(List(IncomingCreditsCanceled(transferId)))
      case None => Left(iMadzError("60009", "incoming credits cannot be found or already be canceled before"))
    }
  }
}
