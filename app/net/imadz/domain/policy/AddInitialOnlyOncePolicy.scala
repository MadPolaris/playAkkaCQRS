package net.imadz.domain.policy

import net.imadz.common.CommonTypes.{DomainPolicy, iMadzError}
import net.imadz.domain.entities.CreditBalanceEntity.{BalanceChanged, CreditBalanceEvent, CreditBalanceState}
import net.imadz.domain.values.Money

object AddInitialOnlyOncePolicy extends DomainPolicy[CreditBalanceEvent, CreditBalanceState, Money] {

  private val InitialConditionNotMeet: iMadzError = iMadzError("60000", "不满足 Initial 条件")

  def apply(creditBalanceState: CreditBalanceState, initial: Money): Either[iMadzError, List[CreditBalanceEvent]] =
    if (creditBalanceState.accountBalance.values.exists(_.amount > 0)) Left(InitialConditionNotMeet)
    else Right(List(BalanceChanged(initial)))

}
