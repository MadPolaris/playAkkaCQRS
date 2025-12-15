package net.imadz.infrastructure.persistence

import akka.persistence.typed.SnapshotAdapter
import net.imadz.application.services.transactor.MoneyTransferTransactionEntity._
import net.imadz.domain.values.Money
import net.imadz.infrastructure.proto.credits.MoneyPO
import net.imadz.infrastructure.proto.transactions._

import java.util.Currency

class TransactionSnapshotAdapter extends SnapshotAdapter[MoneyTransferTransactionState] {

  override def toJournal(state: MoneyTransferTransactionState): Any = {
    val statusPO = state.status match {
      case Status.New =>
        TransactionStatusMessagePO(TransactionStatusPO.NEW)
      case Status.Initiated =>
        TransactionStatusMessagePO(TransactionStatusPO.INITIATED)
      case Status.Prepared =>
        TransactionStatusMessagePO(TransactionStatusPO.PREPARED) // 注意：Proto Enum 名字可能需要核对，这里假设映射关系
      case Status.Completed =>
        TransactionStatusMessagePO(TransactionStatusPO.COMPLETED)
      case Status.Failed(reason) =>
        TransactionStatusMessagePO(TransactionStatusPO.FAILED, TransactionStatusMessagePO.Details.Failed(FailedStatusDetailsPO(reason)))
    }

    TransactionStatePO(
      id = state.id.getOrElse(""),
      fromUserId = state.fromUserId.getOrElse(""),
      toUserId = state.toUserId.getOrElse(""),
      amount = state.amount.map(toMoneyPO),
      status = Some(statusPO)
    )
  }

  override def fromJournal(from: Any): MoneyTransferTransactionState = from match {
    case po: TransactionStatePO =>
      val status = po.status.map { s =>
        s.status match {
          case TransactionStatusPO.NEW => Status.New
          case TransactionStatusPO.INITIATED => Status.Initiated
          case TransactionStatusPO.PREPARED => Status.Prepared
          case TransactionStatusPO.COMPLETED => Status.Completed
          case TransactionStatusPO.FAILED =>
            val reason = s.details.failed.map(_.reason).getOrElse("Unknown")
            Status.Failed(reason)
          case _ => Status.New
        }
      }.getOrElse(Status.New)

      MoneyTransferTransactionState(
        id = if (po.id.isEmpty) None else Some(po.id),
        fromUserId = if (po.fromUserId.isEmpty) None else Some(po.fromUserId),
        toUserId = if (po.toUserId.isEmpty) None else Some(po.toUserId),
        amount = Option(po.amount).flatMap(_.map(fromMoneyPO)),
        status = status
      )
  }

  private def toMoneyPO(money: Money): MoneyPO =
    MoneyPO(money.amount.doubleValue, money.currency.getCurrencyCode)

  private def fromMoneyPO(po: MoneyPO): Money =
    Money(BigDecimal(po.amount), Currency.getInstance(po.currency))
}