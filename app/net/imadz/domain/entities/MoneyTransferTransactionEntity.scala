package net.imadz.application.services.transactor

import net.imadz.common.CborSerializable
import net.imadz.domain.values.Money

object MoneyTransferTransactionEntity {

  // --- 1. State ---
  sealed trait Status extends CborSerializable
  object Status {
    case object New extends Status
    case object Initiated extends Status
    case object Prepared extends Status // 可选，看 Saga 粒度
    case object Completed extends Status
    case class Failed(reason: String) extends Status
  }

  case class MoneyTransferTransactionState(
                                            id: Option[String] = None,
                                            fromUserId: Option[String] = None,
                                            toUserId: Option[String] = None,
                                            amount: Option[Money] = None,
                                            status: Status = Status.New
                                          ) extends CborSerializable {

    def applyEvent(event: MoneyTransferTransactionEvent): MoneyTransferTransactionState = event match {
      case TransactionInitiated(from, to, amt, _) =>
        // 注意：ID 的赋值通常在 Entity 创建时或由 PersistenceId 决定，这里主要更新业务字段
        copy(fromUserId = Some(from), toUserId = Some(to), amount = Some(amt), status = Status.Initiated)

      case TransactionPrepared(txId, _) =>
        copy(id = Some(txId), status = Status.Prepared)

      case TransactionCompleted(_, _) =>
        copy(status = Status.Completed)

      case TransactionFailed(_, reason, _) =>
        copy(status = Status.Failed(reason))
    }
  }

  // --- 2. Events ---
  sealed trait MoneyTransferTransactionEvent extends CborSerializable

  // Initiated 事件现在不带 ID (或者 ID 由 PersistenceId 隐含)，为了方便查询，带上也无妨
  case class TransactionInitiated(fromUserId: String, toUserId: String, amount: Money, timestamp: Long) extends MoneyTransferTransactionEvent
  case class TransactionPrepared(id: String, timestamp: Long) extends MoneyTransferTransactionEvent
  case class TransactionCompleted(id: String, timestamp: Long) extends MoneyTransferTransactionEvent
  case class TransactionFailed(id: String, reason: String, timestamp: Long) extends MoneyTransferTransactionEvent
}