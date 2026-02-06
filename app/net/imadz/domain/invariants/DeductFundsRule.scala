package net.imadz.domain.invariants

import net.imadz.common.CommonTypes.{InvariantRule, Id, iMadzError}
import net.imadz.domain.entities.CreditBalanceEntity._

object DeductFundsRule extends InvariantRule[CreditBalanceEvent, CreditBalanceState, Id] {
  def apply(state: CreditBalanceState, transferId: Id): Either[iMadzError, List[CreditBalanceEvent]] = {
    state.reservedAmount.get(transferId) match {
      case Some(reservedAmount) =>
        Right(List(FundsDeducted(transferId, reservedAmount)))
      case None =>
        Left(iMadzError("60006", "No reserved funds found for this transfer"))
    }
  }
}
