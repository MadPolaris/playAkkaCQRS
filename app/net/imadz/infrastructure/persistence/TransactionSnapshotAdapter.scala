package net.imadz.infrastructure.persistence

import akka.persistence.typed.SnapshotAdapter
import net.imadz.common.Id
import net.imadz.domain.entities.TransactionEntity._
import net.imadz.domain.values.Money
import net.imadz.infrastructure.proto.credits.MoneyPO
import net.imadz.infrastructure.proto.transactions._

import java.util.Currency

class TransactionSnapshotAdapter extends SnapshotAdapter[TransactionState] {

  override def toJournal(state: TransactionState): Any = {
    TransactionStatePO(
      id = state.id.toString,
      fromUserId = state.fromUserId.map(_.toString).getOrElse(""),
      toUserId = state.toUserId.map(_.toString).getOrElse(""),
      amount = state.amount.map(m => MoneyPO(m.amount.doubleValue, m.currency.getCurrencyCode)),
      status = writeStatus(state.status)
    )
  }

  private def writeStatus(status: TransactionStatus): TransactionStatusPO = status match {
    case New => TransactionStatusPO.NEW
    case Initiated => TransactionStatusPO.INITIATED
    case Prepared => TransactionStatusPO.PREPARED
    case Completed => TransactionStatusPO.COMPLETED
    case Failed => TransactionStatusPO.FAILED
  }

  override def fromJournal(from: Any): TransactionState = from match {
    case po: TransactionStatePO =>
      TransactionState(
        id = Id.of(po.id),
        fromUserId = if (po.fromUserId.isEmpty) None else Some(Id.of(po.fromUserId)),
        toUserId = if (po.toUserId.isEmpty) None else Some(Id.of(po.toUserId)),
        amount = po.amount.map(money => Money(BigDecimal(money.amount), Currency.getInstance(money.currency))),
        status = po.status match {
          case TransactionStatusPO.NEW => New
          case TransactionStatusPO.INITIATED => Initiated
          case TransactionStatusPO.PREPARED => Prepared
          case TransactionStatusPO.COMPLETED => Completed
          case TransactionStatusPO.FAILED => Failed
        }
      )
    case unknown => throw new IllegalStateException(s"Unknown journal type: ${unknown.getClass.getName}")
  }
}
