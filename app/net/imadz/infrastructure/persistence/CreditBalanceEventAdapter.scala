package net.imadz.infrastructure.persistence

import akka.persistence.typed.{EventAdapter, EventSeq}
import net.imadz.common.Id
import net.imadz.domain.entities.CreditBalanceEntity._
import net.imadz.domain.values.Money
import net.imadz.infrastructure.proto.credits._

import java.util.Currency

class CreditBalanceEventAdapter extends EventAdapter[CreditBalanceEvent, CreditBalanceEventPO.Event] {

  override def toJournal(e: CreditBalanceEvent): CreditBalanceEventPO.Event =
    e match {
      case BalanceChanged(update, timestamp) =>
        CreditBalanceEventPO.Event.BalanceChanged(
          BalanceChangedPO(
            Some(toMoneyPO(update)),
            timestamp
          )
        )
      case FundsReserved(transferId, amount) =>
        CreditBalanceEventPO.Event.FundsReserved(
          FundsReservedPO(
            transferId.toString,
            Some(toMoneyPO(amount))
          )
        )
      case FundsDeducted(transferId, amount) =>
        CreditBalanceEventPO.Event.FundsDeducted(
          FundsDeductedPO(
            transferId.toString,
            Some(toMoneyPO(amount))
          )
        )
      case ReservationReleased(transferId, amount) =>
        CreditBalanceEventPO.Event.ReservationReleased(
          ReservationReleasedPO(
            transferId.toString,
            Some(toMoneyPO(amount))
          )
        )
      case IncomingCreditsRecorded(transferId, amount) =>
        CreditBalanceEventPO.Event.IncomingCreditsRecorded(
          IncomingCreditsRecordedPO(
            transferId.toString,
            Some(toMoneyPO(amount))
          )
        )
      case IncomingCreditsCommited(transferId) =>
        CreditBalanceEventPO.Event.IncomingCreditsCommited(
          IncomingCreditsCommitedPO(
            transferId.toString
          )
        )
      case IncomingCreditsCanceled(transferId) =>
        CreditBalanceEventPO.Event.IncomingCreditsCanceled(
          IncomingCreditsCanceledPO(
            transferId.toString
          )
        )
    }

  override def manifest(event: CreditBalanceEvent): String = event.getClass.getName

  override def fromJournal(p: CreditBalanceEventPO.Event, manifest: String): EventSeq[CreditBalanceEvent] =
    p match {
      case CreditBalanceEventPO.Event.BalanceChanged(po) =>
        EventSeq.single(BalanceChanged(fromMoneyPO(po.getUpdate), po.timestamp))

      case CreditBalanceEventPO.Event.FundsReserved(po) =>
        EventSeq.single(FundsReserved(Id.of(po.transferId), fromMoneyPO(po.getAmount)))

      case CreditBalanceEventPO.Event.FundsDeducted(po) =>
        EventSeq.single(FundsDeducted(Id.of(po.transferId), fromMoneyPO(po.getAmount)))

      case CreditBalanceEventPO.Event.ReservationReleased(po) =>
        EventSeq.single(ReservationReleased(Id.of(po.transferId), fromMoneyPO(po.getAmount)))

      case CreditBalanceEventPO.Event.IncomingCreditsRecorded(po) =>
        EventSeq.single(IncomingCreditsRecorded(Id.of(po.transferId), fromMoneyPO(po.getAmount)))

      case CreditBalanceEventPO.Event.IncomingCreditsCommited(po) =>
        EventSeq.single(IncomingCreditsCommited(Id.of(po.transferId)))

      case CreditBalanceEventPO.Event.IncomingCreditsCanceled(po) =>
        EventSeq.single(IncomingCreditsCanceled(Id.of(po.transferId)))

      case _ =>
        EventSeq.empty
    }

  // Helper methods for Money conversion
  private def toMoneyPO(money: Money): MoneyPO =
    MoneyPO(money.amount.doubleValue, money.currency.getCurrencyCode)

  private def fromMoneyPO(po: MoneyPO): Money =
    Money(BigDecimal(po.amount), Currency.getInstance(po.currency))
}