package net.imadz.application.aggregates.behaviors

import akka.actor.typed.Scheduler
import akka.actor.typed.scaladsl.ActorContext
import akka.cluster.sharding.typed.scaladsl.EntityRef
import akka.persistence.typed.scaladsl.Effect
import akka.util.Timeout
import net.imadz.application.aggregates.MoneyTransferTransactionAggregate._
import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.common.Id
import net.imadz.common.application.saga.TransactionCoordinator
import net.imadz.common.application.saga.TransactionCoordinator.{CommitPhase, CompensatePhase, PreparePhase, Transaction, TransactionResponse}
import net.imadz.domain.entities.TransactionEntity._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

object MoneyTransferTransactionBehaviors {
  implicit val askTimeout: Timeout = Timeout(30 seconds)

  def apply(context: ActorContext[MoneyTransferTransactionCommand], coordinator: EntityRef[TransactionCoordinator.SagaCommand], repository: CreditBalanceRepository)(implicit ec: ExecutionContext, scheduler: Scheduler): TransactionCommandHandler = (state, command) => command match {
    case InitiateMoneyTransferTransaction(fromUserId, toUserId, amount, replyTo) =>
      val transactionId = Id.gen.toString
      val steps = createTransactionSteps(fromUserId, toUserId, amount, repository)
      val transaction = Transaction(
        id = transactionId,
        steps = steps,
        phases = Seq(PreparePhase.key, CommitPhase.key, CompensatePhase.key))
      Effect.persist(TransactionInitiated(fromUserId, toUserId, amount))
        .thenRun { _ =>
          coordinator.ask[TransactionResponse](intermediateReplyTo => TransactionCoordinator.StartTransaction(transaction, intermediateReplyTo))
            .mapTo[TransactionResponse]
            .foreach(context.self ! UpdateMoneyTransferTransactionStatus(Id.of(transactionId), _, replyTo))
        }
    case UpdateMoneyTransferTransactionStatus(id, transactionResponse, replyTo) => transactionResponse match {
      case TransactionResponse.Completed(id) =>
        Effect.persist(TransactionCompleted(Id.of(id)))
          .thenReply[TransactionResultConfirmation](replyTo)(_ => TransactionResultConfirmation(Id.of(id), None))
      case TransactionResponse.Failed(id, reason) =>
        Effect.persist(TransactionFailed(Id.of(id), reason))
          .thenReply[TransactionResultConfirmation](replyTo)(_ => TransactionResultConfirmation(Id.of(id), Some(reason)))
    }
  }
}