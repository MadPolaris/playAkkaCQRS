package net.imadz.domain.policy

import net.imadz.common.CommonTypes.{DomainPolicy, iMadzError}
import net.imadz.domain.entities.CreditBalanceEntity.{BalanceChanged, CreditBalanceEvent, CreditBalanceState}
import net.imadz.domain.values.Money

object WithdrawPolicy extends DomainPolicy[CreditBalanceEvent, CreditBalanceState, Money] {

  private val NotEnoughBalance: iMadzError = iMadzError("60002", "balance 不足扣减 或 扣减值应该为正数")

  def apply(creditBalanceState: CreditBalanceState, withdrawAmount: Money): Either[iMadzError, List[CreditBalanceEvent]] =
    if (creditBalanceState.accountBalance
      .get(withdrawAmount.currency.getCurrencyCode)
      .flatMap(withdrawAmount <= _)
      .getOrElse(false)) Right(List(BalanceChanged(withdrawAmount.copy(amount = -withdrawAmount.amount))))
    else
      Left(NotEnoughBalance)

}
