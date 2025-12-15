package net.imadz.infrastructure.persistence

import akka.persistence.typed.SnapshotAdapter
import net.imadz.common.Id
import net.imadz.domain.entities.CreditBalanceEntity._
import net.imadz.domain.values.Money
import net.imadz.infrastructure.proto.credits.credits._

import java.util.{Currency, UUID}

class CreditBalanceSnapshotAdapter extends SnapshotAdapter[CreditBalanceState] {

  override def toJournal(state: CreditBalanceState): Any = {
    CreditBalanceStatePO(
      userId = state.userId.toString,
      accountBalance = state.accountBalance.map { case (k, v) => k -> toMoneyPO(v) },
      reservedAmount = state.reservedAmount.map { case (k, v) => k.toString -> toMoneyPO(v) },
      incomingCredits = state.incomingCredits.map { case (k, v) => k.toString -> toMoneyPO(v) }
    )
  }

  override def fromJournal(from: Any): CreditBalanceState = from match {
    case po: CreditBalanceStatePO =>
      CreditBalanceState(
        userId = UUID.fromString(po.userId),
        accountBalance = po.accountBalance.map { case (k, v) => k -> fromMoneyPO(v) }.toMap,
        reservedAmount = po.reservedAmount.map { case (k, v) => Id.of(k) -> fromMoneyPO(v) }.toMap,
        incomingCredits = po.incomingCredits.map { case (k, v) => Id.of(k) -> fromMoneyPO(v) }.toMap
      )
    case unknown => throw new IllegalStateException(s"Unknown journal type: ${unknown.getClass.getName}")
  }

  private def toMoneyPO(money: Money): MoneyPO =
    MoneyPO(money.amount.doubleValue, money.currency.getCurrencyCode)

  private def fromMoneyPO(po: MoneyPO): Money =
    Money(BigDecimal(po.amount), Currency.getInstance(po.currency))
}