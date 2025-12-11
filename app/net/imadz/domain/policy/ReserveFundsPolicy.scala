package net.imadz.domain.policy

import net.imadz.common.CommonTypes.{DomainPolicy, Id, iMadzError}
import net.imadz.domain.entities.CreditBalanceEntity.{CreditBalanceEvent, CreditBalanceState, FundsReserved}
import net.imadz.domain.services.TransferDomainService
import net.imadz.domain.values.Money

object ReserveFundsPolicy extends DomainPolicy[CreditBalanceEvent, CreditBalanceState, (Id, Money)] {
  def apply(state: CreditBalanceState, param: (Id, Money)): Either[iMadzError, List[CreditBalanceEvent]] = {
    val (transferId, amount) = param
    val currentBalance = state.accountBalance.getOrElse(amount.currency.getCurrencyCode, Money(BigDecimal(0), amount.currency))

    TransferDomainService.validateTransfer(transferId, state.reservedAmount, currentBalance, amount) match {
      case Right(_) =>
        Right(List(FundsReserved(transferId, amount)))

      // [Magic] 原逻辑中 60008 表示“已存在/幂等”，此时回复成功但不做操作
      // 在 Policy 中，这对应于 Right(Nil) -> 无事件产生
      case Left(err) if err.code == "60008" =>
        Right(Nil)

      case Left(error) =>
        Left(error)
    }
  }
}