package net.imadz.application.services.transactor

import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.{EntityRef, EntityTypeKey}
import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.application.services.transactor.behaviors.MoneyTransferSagaTransactorBehaviors
import net.imadz.common.CborSerializable
import net.imadz.common.CommonTypes.Id
import net.imadz.domain.values.Money
import net.imadz.infra.saga.SagaTransactionCoordinator
import net.imadz.infra.saga.SagaTransactionCoordinator.TracingStep
import play.api.libs.json.{Json, OWrites}

object MoneyTransferSagaTransactor {

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

  // --- Replies ---
  case class TransactionResultConfirmation(
                                            transactionId: Id,
                                            error: Option[String],
                                            tracing: List[TracingStep]
                                          ) extends CborSerializable

  object TransactionResultConfirmation {
    implicit val confirmationWrites: OWrites[TransactionResultConfirmation] = Json.writes[TransactionResultConfirmation]
  }

  // --- Sharding Key ---
  val entityTypeKey: EntityTypeKey[MoneyTransferTransactionCommand] = EntityTypeKey("MoneyTransferTransaction")

  // --- Factory ---
  def apply(
             id: String,
             coordinator: EntityRef[SagaTransactionCoordinator.Command],
             repository: CreditBalanceRepository
           ): Behavior[MoneyTransferTransactionCommand] = {
    // 委托给分离的行为对象
    MoneyTransferSagaTransactorBehaviors.apply(id, coordinator, repository)
  }
}