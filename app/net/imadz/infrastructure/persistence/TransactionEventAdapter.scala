package net.imadz.infrastructure.persistence

import akka.persistence.typed.{EventAdapter, EventSeq}
// [UPDATED] 引入 Entity 中的 Events
import net.imadz.domain.entities.MoneyTransferTransactionEntity._
import net.imadz.domain.values.Money
import net.imadz.infrastructure.proto.credits.MoneyPO
import net.imadz.infrastructure.proto.transactions._

import java.util.Currency

class TransactionEventAdapter extends EventAdapter[MoneyTransferTransactionEvent, TransactionEventPO.Event] {

  override def toJournal(e: MoneyTransferTransactionEvent): TransactionEventPO.Event = e match {
    case TransactionInitiated(fromUserId, toUserId, amount, timestamp) =>
      TransactionEventPO.Event.Initiated(
        TransactionInitiatedPO(fromUserId, toUserId, Some(toMoneyPO(amount)), timestamp)
      )
    case TransactionPrepared(id, timestamp) =>
      TransactionEventPO.Event.Prepared(TransactionPreparedPO(id, timestamp))
    case TransactionCompleted(id, timestamp) =>
      TransactionEventPO.Event.Completed(TransactionCompletedPO(id, timestamp))
    case TransactionFailed(id, reason, timestamp) =>
      TransactionEventPO.Event.Failed(TransactionFailedPO(id, reason, timestamp))
  }

  override def manifest(event: MoneyTransferTransactionEvent): String = event.getClass.getName

  override def fromJournal(p: TransactionEventPO.Event, manifest: String): EventSeq[MoneyTransferTransactionEvent] = p match {
    case TransactionEventPO.Event.Initiated(po) =>
      EventSeq.single(TransactionInitiated(po.fromUserId, po.toUserId, fromMoneyPO(po.getAmount), po.timestamp))
    case TransactionEventPO.Event.Prepared(po) =>
      EventSeq.single(TransactionPrepared(po.id, po.timestamp))
    case TransactionEventPO.Event.Completed(po) =>
      EventSeq.single(TransactionCompleted(po.id, po.timestamp))
    case TransactionEventPO.Event.Failed(po) =>
      EventSeq.single(TransactionFailed(po.id, po.reason, po.timestamp))
    case _ => EventSeq.empty
  }

  private def toMoneyPO(money: Money): MoneyPO =
    MoneyPO(money.amount.doubleValue, money.currency.getCurrencyCode)

  private def fromMoneyPO(po: MoneyPO): Money =
    Money(BigDecimal(po.amount), Currency.getInstance(po.currency))
}