package net.imadz.domain.policy

import net.imadz.common.CommonTypes.{DomainPolicy, iMadzError}
import net.imadz.domain.entities.CreditBalanceEntity.{BalanceChanged, CreditBalanceEvent, CreditBalanceState}
import net.imadz.domain.values.Money

object DepositPolicy extends DomainPolicy[CreditBalanceEvent, CreditBalanceState, Money] {
  private val ChangeShouldBePositive: iMadzError = iMadzError("60001", "change 需要为正数")

  def apply(state: CreditBalanceState, change: Money): Either[iMadzError, List[CreditBalanceEvent]] =
    if (change.amount <= 0) Left(ChangeShouldBePositive)
    else Right(List(BalanceChanged(change)))
}
