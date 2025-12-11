

package net.imadz.domain.policy

import net.imadz.common.CommonTypes.{DomainPolicy, Id, iMadzError}
import net.imadz.domain.entities.CreditBalanceEntity._

object DeductFundsPolicy extends DomainPolicy[CreditBalanceEvent, CreditBalanceState, Id] {
  def apply(state: CreditBalanceState, transferId: Id): Either[iMadzError, List[CreditBalanceEvent]] = {
    state.reservedAmount.get(transferId) match {
      case Some(reservedAmount) =>
        Right(List(FundsDeducted(transferId, reservedAmount)))
      case None =>
        Left(iMadzError("60006", "No reserved funds found for this transfer"))
    }
  }
}