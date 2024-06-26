package net.imadz.infrastructure.persistence

import akka.persistence.typed.{EventAdapter, EventSeq}
import net.imadz.domain.entities.CreditBalanceEntity._
import net.imadz.domain.values.Money
import net.imadz.infrastructure.proto.credits.{CreditEventPO, MoneyPO}

import java.util.Currency

class CreditBalanceEventAdapter extends EventAdapter[CreditBalanceEvent, CreditEventPO.Event] {

  override def toJournal(e: CreditBalanceEvent): CreditEventPO.Event =
    e match {
      case BalanceChanged(update, timestamp) =>

        CreditEventPO.Event.BalanceChangedPO(
          net.imadz.infrastructure.proto.credits.BalanceChanged(
            Some(MoneyPO(update.amount.doubleValue, update.currency.getCurrencyCode)),
            timestamp
          )
        )
    }

  override def manifest(event: CreditBalanceEvent): String = event.getClass.getName

  override def fromJournal(p: CreditEventPO.Event, manifest: String): EventSeq[CreditBalanceEvent] =
    p match {
      case CreditEventPO.Event.BalanceChangedPO(po) =>
        EventSeq.single(BalanceChanged(
          po.update.map((moneyPO: MoneyPO) => {
            val currency = Currency.getInstance(moneyPO.currency)
            Money(BigDecimal(moneyPO.amount), currency)
          }).get, po.timestamp)
        )
      case _ =>
        EventSeq.empty
    }
}