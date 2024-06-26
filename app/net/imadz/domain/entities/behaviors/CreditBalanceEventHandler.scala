package net.imadz.domain.entities.behaviors

import net.imadz.domain.entities.CreditBalanceEntity.{BalanceChanged, CreditBalanceEventHandler, CreditBalanceState}
import net.imadz.domain.values.Money

import java.util.Currency

import net.imadz.domain.entities.CreditBalanceEntity._

object CreditBalanceEventHandler {
  def apply: CreditBalanceEventHandler = (state, event) => event match {
    case BalanceChanged(updateMoney@Money(_, currency), _) =>
      state.copy(accountBalance = updateAccountBalance(state, currency, updateMoney))

    case FundsReserved(transferId, reserveAmount@Money(_, currency)) =>
      state.copy(
        accountBalance = updateAccountBalance(state, currency, reserveAmount.copy(amount = -reserveAmount.amount)),
        reservedAmount = state.reservedAmount + (transferId -> reserveAmount)
      )

    case ReservationReleased(transferId, releaseAmount@Money(_, currency)) =>
      state.copy(
        accountBalance = updateAccountBalance(state, currency, releaseAmount),
        reservedAmount = state.reservedAmount - transferId
      )

    case TransferCompleted(transferId) =>
      state.copy(
        reservedAmount = state.reservedAmount - transferId
      )
  }

  private def updateAccountBalance(state: CreditBalanceState, currency: Currency, updateMoney: Money): Map[String, Money] = {
    val currentBalance = state.accountBalance.getOrElse(currency.getCurrencyCode, Money(BigDecimal(0), currency))
    state.accountBalance + (currency.getCurrencyCode -> (currentBalance + updateMoney).getOrElse(Money(BigDecimal(0), currency)))
  }

  private def defaultMoney(currency: Currency) = {
    Money(BigDecimal(0), currency)
  }

  private def originalMoney(balanceMap: Map[String, Money], currency: Currency): Money = {
    balanceMap.getOrElse(currency.getCurrencyCode, defaultMoney(currency))
  }
}