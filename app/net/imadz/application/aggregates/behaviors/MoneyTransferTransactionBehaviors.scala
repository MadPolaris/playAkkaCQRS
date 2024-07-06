package net.imadz.application.aggregates.behaviors

import akka.actor.typed.Scheduler
import akka.actor.typed.scaladsl.ActorContext
import akka.cluster.sharding.typed.scaladsl.EntityRef
import akka.persistence.typed.scaladsl.Effect
import akka.util.Timeout
import net.imadz.application.aggregates.MoneyTransferTransactionAggregate._
import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.common.CommonTypes.iMadzError
import net.imadz.common.Id
import net.imadz.domain.entities.TransactionEntity._
import net.imadz.infra.saga.SagaTransactionCoordinator
import net.imadz.infra.saga.SagaTransactionCoordinator.TransactionResult

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

object MoneyTransferTransactionBehaviors {
  implicit val askTimeout: Timeout = Timeout(30 seconds)

  def apply(context: ActorContext[MoneyTransferTransactionCommand], coordinator: EntityRef[SagaTransactionCoordinator.Command], repository: CreditBalanceRepository)(implicit ec: ExecutionContext, scheduler: Scheduler): TransactionCommandHandler = (state, command) => command match {
    case InitiateMoneyTransferTransaction(fromUserId, toUserId, amount, replyTo) =>
      val transactionId = Id.gen.toString
      val steps = createTransactionSteps(fromUserId, toUserId, amount, repository)

      Effect.persist(TransactionInitiated(fromUserId, toUserId, amount))
        .thenRun { _ =>
          coordinator.ask[TransactionResult](intermediateReplyTo => SagaTransactionCoordinator.StartTransaction[iMadzError, String](transactionId, steps, Some(intermediateReplyTo)))
            .mapTo[TransactionResult]
            .foreach(context.self ! UpdateMoneyTransferTransactionStatus(Id.of(transactionId), _, replyTo))
        }
    case UpdateMoneyTransferTransactionStatus(id, transactionResponse, replyTo) =>
      if (transactionResponse.successful)
        Effect.persist(TransactionCompleted(id))
          .thenReply[TransactionResultConfirmation](replyTo)(_ => TransactionResultConfirmation(id, None))
      else {

        Effect.persist(TransactionFailed(id, transactionResponse.failReason))
          .thenReply[TransactionResultConfirmation](replyTo)(_ => TransactionResultConfirmation(id, Some(transactionResponse.failReason)))
      }
  }
}