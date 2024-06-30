package net.imadz.application.aggregates

import akka.actor.typed.ActorRef
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.typed.scaladsl.Effect
import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.common.CommonTypes.Id
import net.imadz.common.application.saga.TransactionCoordinator.{Participant, TransactionResponse, TransactionStep}
import net.imadz.common.{CborSerializable, Id}
import net.imadz.domain.entities.TransactionEntity._
import net.imadz.domain.values.Money

import scala.concurrent.ExecutionContext

object MoneyTransferTransactionAggregate {
  // Commands
  sealed trait MoneyTransferTransactionCommand

  case class InitiateMoneyTransferTransaction(fromUserId: Id, toUserId: Id, amount: Money, replyTo: ActorRef[TransactionResultConfirmation]) extends MoneyTransferTransactionCommand

  case class UpdateMoneyTransferTransactionStatus(id: Id, newStatus: TransactionResponse, replyTo: ActorRef[TransactionResultConfirmation]) extends MoneyTransferTransactionCommand

  // Command Replies
  case class TransactionResultConfirmation(transactionId: Id, error: Option[String]) extends CborSerializable

  // Command Handler
  type TransactionCommandHandler = (TransactionState, MoneyTransferTransactionCommand) => Effect[TransactionEvent, TransactionState]

  // Akka
  val TransactionEntityTypeKey: EntityTypeKey[MoneyTransferTransactionCommand] = EntityTypeKey("Transaction")

  // Transaction Steps
  def createTransactionSteps(fromUserId: Id, toUserId: Id, amount: Money, repository: CreditBalanceRepository)(implicit ec: ExecutionContext): Seq[TransactionStep] = {
    val fromAccountParticipant = new FromAccountParticipant(fromUserId: Id, amount: Money, repository)
    val toAccountParticipant = new ToAccountParticipant(toUserId: Id, amount: Money, repository)

    Seq(
      TransactionStep("reserve-amount-from-account", "prepare", fromAccountParticipant),
      TransactionStep("record-incoming-amount-to-account", "prepare", toAccountParticipant),
      TransactionStep("commit-from-account", "commit", fromAccountParticipant),
      TransactionStep("commit-to-account", "commit", toAccountParticipant),
      TransactionStep("compensate-from-account", "compensate", fromAccountParticipant),
      TransactionStep("compensate-to-account", "compensate", toAccountParticipant)
    )
  }

  // Participants

  import akka.util.Timeout
  import net.imadz.application.aggregates.CreditBalanceAggregate._

  import scala.concurrent.duration._
  import scala.concurrent.{ExecutionContext, Future}

  case class FromAccountParticipant(fromUserId: Id, amount: Money, repo: CreditBalanceRepository)(implicit ec: ExecutionContext) extends Participant {

    implicit val timeout: Timeout = 5.seconds
    private val fromAccountRef = repo.findCreditBalanceByUserId(fromUserId)

    override def prepare(transactionId: String, context: Map[String, Any]): Future[Boolean] = {
      executeWithRetryClassification(
        fromAccountRef.ask(CreditBalanceAggregate.ReserveFunds(Id.of(transactionId), amount, _))
          .mapTo[FundsReservationConfirmation]
          .map(_.error.isEmpty)
      )
    }

    override def commit(transactionId: String, context: Map[String, Any]): Future[Boolean] = {
      executeWithRetryClassification(
        fromAccountRef.ask(CreditBalanceAggregate.DeductFunds(Id.of(transactionId), _))
          .mapTo[FundsDeductionConfirmation]
          .map(_.error.isEmpty)
      )
    }

    override def compensate(transactionId: String, context: Map[String, Any]): Future[Boolean] = {
      executeWithRetryClassification(
        fromAccountRef.ask(CreditBalanceAggregate.ReleaseReservedFunds(Id.of(transactionId), _))
          .mapTo[FundsReleaseConfirmation]
          .map(_.error.isEmpty)
      )
    }
  }

  case class ToAccountParticipant(toUserId: Id, amount: Money, repo: CreditBalanceRepository)(implicit ec: ExecutionContext) extends Participant {
    private val toAccountRef = repo.findCreditBalanceByUserId(toUserId)

    implicit val timeout: Timeout = 5.seconds

    override def prepare(transactionId: String, context: Map[String, Any]): Future[Boolean] = {
      executeWithRetryClassification(
        toAccountRef.ask(CreditBalanceAggregate.RecordIncomingCredits(Id.of(transactionId), amount, _))
          .mapTo[RecordIncomingCreditsConfirmation]
          .map(_.error.isEmpty)
      )
    }

    override def commit(transactionId: String, context: Map[String, Any]): Future[Boolean] = {
      executeWithRetryClassification(
        toAccountRef.ask(CreditBalanceAggregate.CommitIncomingCredits(Id.of(transactionId), _))
          .mapTo[CommitIncomingCreditsConfirmation]
          .map(_.error.isEmpty)
      )
    }

    override def compensate(transactionId: String, context: Map[String, Any]): Future[Boolean] = {
      executeWithRetryClassification(
        toAccountRef.ask(CreditBalanceAggregate.CancelIncomingCredit(Id.of(transactionId), _))
          .mapTo[CancelIncomingCreditConfirmation]
          .map(_.error.isEmpty)
      )
    }
  }


}