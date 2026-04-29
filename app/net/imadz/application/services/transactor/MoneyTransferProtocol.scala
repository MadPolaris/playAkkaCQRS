package net.imadz.application.services.transactor

import akka.actor.typed.ActorRef
import net.imadz.common.CborSerializable
import net.imadz.common.CommonTypes.Id
import net.imadz.domain.values.Money
import net.imadz.infra.saga.SagaTransactionCoordinator
import net.imadz.infra.saga.SagaTransactionCoordinator.TracingStep
import play.api.libs.json.{Json, OWrites}

object MoneyTransferProtocol {

  // --- Commands ---
  sealed trait MoneyTransferTransactionCommand extends CborSerializable

  case class InitiateMoneyTransferTransaction(
                                               fromUserId: Id,
                                               toUserId: Id,
                                               amount: Money,
                                               replyTo: ActorRef[TransactionResultConfirmation]
                                             ) extends MoneyTransferTransactionCommand

  // 内部命令：用于接收 Coordinator 的异步结果
  case class UpdateMoneyTransferTransactionStatus(
                                                   id: Id,
                                                   newStatus: SagaTransactionCoordinator.TransactionResult,
                                                   replyTo: ActorRef[TransactionResultConfirmation]
                                                 ) extends MoneyTransferTransactionCommand

  // 管理员命令：手动修复特定步骤
  case class AdminManualFixStep(
                                 stepId: String,
                                 phase: String,
                                 replyTo: ActorRef[TransactionResultConfirmation]
                               ) extends MoneyTransferTransactionCommand

  // 管理员命令：在手动修复后恢复事务执行
  case class AdminResumeTransaction(
                                     replyTo: ActorRef[TransactionResultConfirmation]
                                   ) extends MoneyTransferTransactionCommand

  // --- Replies ---
  case class TransactionResultConfirmation(
                                            transactionId: Id,
                                            error: Option[String],
                                            tracing: List[TracingStep]
                                          ) extends CborSerializable

  object TransactionResultConfirmation {
    implicit val confirmationWrites: OWrites[TransactionResultConfirmation] = Json.writes[TransactionResultConfirmation]
  }
}