package net.imadz.infrastructure.persistence

import akka.persistence.typed.{EventAdapter, EventSeq}
import net.imadz.common.CommonTypes.Id
import net.imadz.common.Id
import net.imadz.domain.entities.TransactionEntity._
import net.imadz.domain.values.Money
import net.imadz.infrastructure.proto.credits.MoneyPO
import net.imadz.infrastructure.proto.transactions._

import java.util.Currency

class TransactionEventAdapter extends EventAdapter[TransactionEvent, TransactionEventPO.Event] {

  implicit def idToString: Id => String = _.toString
  implicit def stringToId: String => Id = Id.of

  override def toJournal(e: TransactionEvent): TransactionEventPO.Event = {
    e match {
      case TransactionInitiated(fromUserId, toUserId, amount) =>
        TransactionEventPO.Event.TransactionInitiatedPO(
          TransactionInitiatedPO(
            fromUserId.toString,
            toUserId.toString,
            Some(MoneyPO(amount.amount.doubleValue, amount.currency.getCurrencyCode))
          )
        )
      case TransactionPrepared(id) =>
        TransactionEventPO.Event.TransactionPreparedPO(
          TransactionPreparedPO(id)
        )
      case TransactionCompleted(id) =>
        TransactionEventPO.Event.TransactionCompletedPO(
          TransactionCompletedPO(id)
        )
      case TransactionFailed(id, reason) =>
        TransactionEventPO.Event.TransactionFailedPO(
          TransactionFailedPO(id, reason)
        )
    }
  }

  override def manifest(event: TransactionEvent): String = event.getClass.getName

  override def fromJournal(p: TransactionEventPO.Event, manifest: String): EventSeq[TransactionEvent] = {
    p match {
      case TransactionEventPO.Event.TransactionInitiatedPO(po) =>
        EventSeq.single(TransactionInitiated(
          Id.of(po.fromUserId),
          Id.of(po.toUserId),
          Money(amount = BigDecimal(po.getAmount.amount), currency = Currency.getInstance(po.getAmount.currency))
        ))
      case TransactionEventPO.Event.TransactionPreparedPO(po) =>
        EventSeq.single(TransactionPrepared(po.id))
      case TransactionEventPO.Event.TransactionCompletedPO(po) =>
        EventSeq.single(TransactionCompleted(po.id))
      case TransactionEventPO.Event.TransactionFailedPO(po) =>
        EventSeq.single(TransactionFailed(po.id, po.reason))
      case _ =>
        EventSeq.empty
    }
  }
}
