package net.imadz.infrastructure.persistence

import akka.persistence.typed.{EventAdapter, EventSeq}
import net.imadz.common.Id
import net.imadz.domain.entities.CreditBalanceEntity._
import net.imadz.domain.values.Money
import net.imadz.infrastructure.proto.credits.{CreditBalanceEventPO => CreditEventPO, MoneyPO}

import java.util.Currency

class CreditBalanceEventAdapter extends EventAdapter[CreditBalanceEvent, CreditEventPO.Event] {

  override def toJournal(e: CreditBalanceEvent): CreditEventPO.Event =
    e match {
      case BalanceChanged(update, timestamp) =>
        CreditEventPO.Event.BalanceChanged(
          net.imadz.infrastructure.proto.credits.BalanceChanged(
            Some(MoneyPO(update.amount.doubleValue, update.currency.getCurrencyCode)),
            timestamp
          )
        )
      case FundsReserved(transferId, amount) =>
        CreditEventPO.Event.FundsReserved(
          net.imadz.infrastructure.proto.credits.FundsReserved(
            transferId.toString,
            Some(MoneyPO(amount.amount.doubleValue, amount.currency.getCurrencyCode))
          )
        )
      case FundsDeducted(transferId, amount) =>
        CreditEventPO.Event.FundsDeducted(
          net.imadz.infrastructure.proto.credits.FundsDeducted(
            transferId.toString,
            Some(MoneyPO(amount.amount.doubleValue, amount.currency.getCurrencyCode))
          )
        )
      case ReservationReleased(transferId, amount) =>
        CreditEventPO.Event.ReservationReleased(
          net.imadz.infrastructure.proto.credits.ReservationReleased(
            transferId.toString,
            Some(MoneyPO(amount.amount.doubleValue, amount.currency.getCurrencyCode))
          )
        )
      case IncomingCreditsRecorded(transferId, amount) =>
        CreditEventPO.Event.IncomingCreditsRecorded(
          net.imadz.infrastructure.proto.credits.IncomingCreditsRecorded(
            transferId.toString,
            Some(MoneyPO(amount.amount.doubleValue, amount.currency.getCurrencyCode))
          )
        )
      case IncomingCreditsCommited(transferId) =>
        CreditEventPO.Event.IncomingCreditsCommited(
          net.imadz.infrastructure.proto.credits.IncomingCreditsCommited(
            transferId.toString
          )
        )
      case IncomingCreditsCanceled(transferId) =>
        CreditEventPO.Event.IncomingCreditsCanceled(
          net.imadz.infrastructure.proto.credits.IncomingCreditsCanceled(
            transferId.toString
          )
        )
    }

  override def manifest(event: CreditBalanceEvent): String = event.getClass.getName

  override def fromJournal(p: CreditEventPO.Event, manifest: String): EventSeq[CreditBalanceEvent] =
    p match {
      case CreditEventPO.Event.BalanceChanged(po) =>
        EventSeq.single(BalanceChanged(
          po.update.map((moneyPO: MoneyPO) => {
            val currency = Currency.getInstance(moneyPO.currency)
            Money(BigDecimal(moneyPO.amount), currency)
          }).get, po.timestamp)
        )
      case CreditEventPO.Event.FundsReserved(po) =>
        EventSeq.single(FundsReserved(
          Id.of(po.transferId),
          po.amount.map((moneyPO: MoneyPO) => {
            val currency = Currency.getInstance(moneyPO.currency)
            Money(BigDecimal(moneyPO.amount), currency)
          }).get
        ))
      case CreditEventPO.Event.FundsDeducted(po) =>
        EventSeq.single(FundsDeducted(
          Id.of(po.transferId),
          po.amount.map((moneyPO: MoneyPO) => {
            val currency = Currency.getInstance(moneyPO.currency)
            Money(BigDecimal(moneyPO.amount), currency)
          }).get
        ))
      case CreditEventPO.Event.ReservationReleased(po) =>
        EventSeq.single(ReservationReleased(
          Id.of(po.transferId),
          po.amount.map((moneyPO: MoneyPO) => {
            val currency = Currency.getInstance(moneyPO.currency)
            Money(BigDecimal(moneyPO.amount), currency)
          }).get
        ))
      case CreditEventPO.Event.IncomingCreditsRecorded(po) =>
        EventSeq.single(IncomingCreditsRecorded(
          Id.of(po.transferId),
          po.amount.map((moneyPO: MoneyPO) => {
            val currency = Currency.getInstance(moneyPO.currency)
            Money(BigDecimal(moneyPO.amount), currency)
          }).get
        ))
      case CreditEventPO.Event.IncomingCreditsCommited(po) =>
        EventSeq.single(IncomingCreditsCommited(
          Id.of(po.transferId)
        ))
      case CreditEventPO.Event.IncomingCreditsCanceled(po) =>
        EventSeq.single(IncomingCreditsCanceled(
          Id.of(po.transferId)
        ))
      case _ =>
        EventSeq.empty
    }
}