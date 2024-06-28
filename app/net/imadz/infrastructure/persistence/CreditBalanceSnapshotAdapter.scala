package net.imadz.infrastructure.persistence

import akka.persistence.typed.SnapshotAdapter
import net.imadz.common.Id
import net.imadz.domain.entities.CreditBalanceEntity._
import net.imadz.domain.values.Money
import net.imadz.infrastructure.proto.credits._

import java.util.{Currency, UUID}

class CreditBalanceSnapshotAdapter extends SnapshotAdapter[CreditBalanceState] {

  override def toJournal(state: CreditBalanceState): Any = {
    val accountBalance = state.accountBalance.map { case (k, v) =>
      k -> MoneyPO(v.amount.doubleValue, v.currency.getCurrencyCode)
    }
    val reservedAmount = state.reservedAmount.map { case (k, v) =>
      k.toString -> MoneyPO(v.amount.doubleValue, v.currency.getCurrencyCode)
    }
    val incomingCredits = state.incomingCredits.map { case (k, v) =>
      k.toString -> MoneyPO(v.amount.doubleValue, v.currency.getCurrencyCode)
    }
    CreditBalanceStatePO(
      userId = state.userId.toString,
      accountBalance = accountBalance,
      reservedAmount = reservedAmount,
      incomingCredits = incomingCredits
    )
  }

  override def fromJournal(from: Any): CreditBalanceState = from match {
    case po: CreditBalanceStatePO =>
      val accountBalance = po.accountBalance.map { case (k, v) =>
        k -> Money(v.amount, Currency.getInstance(v.currency))
      }
      val reservedAmount = po.reservedAmount.map { case (k, v) =>
        Id.of(k) -> Money(v.amount, Currency.getInstance(v.currency))
      }
      val incomingCredits = po.incomingCredits.map { case (k, v) =>
        Id.of(k) -> Money(v.amount, Currency.getInstance(v.currency))
      }
      CreditBalanceState(
        userId = UUID.fromString(po.userId),
        accountBalance = accountBalance,
        reservedAmount = reservedAmount,
        incomingCredits = incomingCredits
      )
    case unknown => throw new IllegalStateException(s"Unknown journal type: ${unknown.getClass.getName}")
  }
}