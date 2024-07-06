package net.imadz.application.aggregates

import akka.actor.typed.ActorRef
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.typed.scaladsl.Effect
import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.common.CommonTypes.{Id, iMadzError}
import net.imadz.common.{CborSerializable, Id}
import net.imadz.domain.entities.TransactionEntity._
import net.imadz.domain.values.Money
import net.imadz.infra.saga.SagaParticipant._
import net.imadz.infra.saga.SagaPhase.{CommitPhase, CompensatePhase, PreparePhase}
import net.imadz.infra.saga.SagaTransactionCoordinator.TransactionResult
import net.imadz.infra.saga.{SagaParticipant, SagaTransactionStep}

import scala.concurrent.ExecutionContext

object MoneyTransferTransactionAggregate {
  // Commands
  sealed trait MoneyTransferTransactionCommand

  case class InitiateMoneyTransferTransaction(fromUserId: Id, toUserId: Id, amount: Money, replyTo: ActorRef[TransactionResultConfirmation]) extends MoneyTransferTransactionCommand

  case class UpdateMoneyTransferTransactionStatus(id: Id, newStatus: TransactionResult, replyTo: ActorRef[TransactionResultConfirmation]) extends MoneyTransferTransactionCommand

  // Command Replies
  case class TransactionResultConfirmation(transactionId: Id, error: Option[String]) extends CborSerializable

  // Command Handler
  type TransactionCommandHandler = (TransactionState, MoneyTransferTransactionCommand) => Effect[TransactionEvent, TransactionState]

  // Akka
  val TransactionEntityTypeKey: EntityTypeKey[MoneyTransferTransactionCommand] = EntityTypeKey("Transaction")

  // Transaction Steps
  def createTransactionSteps(fromUserId: Id, toUserId: Id, amount: Money, repository: CreditBalanceRepository)(implicit ec: ExecutionContext): List[SagaTransactionStep[iMadzError, String]] = {
    val fromAccountParticipant = new FromAccountParticipant(fromUserId: Id, amount: Money, repository)
    val toAccountParticipant = new ToAccountParticipant(toUserId: Id, amount: Money, repository)

    List(
      SagaTransactionStep("reserve-amount-from-account", PreparePhase, fromAccountParticipant, 5),
      SagaTransactionStep("record-incoming-amount-to-account", PreparePhase, toAccountParticipant, 5),
      SagaTransactionStep("commit-from-account", CommitPhase, fromAccountParticipant, 5),
      SagaTransactionStep("commit-to-account", CommitPhase, toAccountParticipant, 5),
      SagaTransactionStep("compensate-from-account", CompensatePhase, fromAccountParticipant, 5),
      SagaTransactionStep("compensate-to-account", CompensatePhase, toAccountParticipant, 5)
    )
  }

  // Participants

  import akka.util.Timeout
  import net.imadz.application.aggregates.CreditBalanceAggregate._

  import scala.concurrent.duration._
  import scala.concurrent.{ExecutionContext, Future}

  case class FromAccountParticipant(fromUserId: Id, amount: Money, repo: CreditBalanceRepository)(implicit ec: ExecutionContext) extends SagaParticipant[iMadzError, String] {

    implicit val timeout: Timeout = 5.seconds
    private val fromAccountRef = repo.findCreditBalanceByUserId(fromUserId)

    override def doPrepare(transactionId: String): ParticipantEffect[iMadzError, String] = {
      fromAccountRef.ask(CreditBalanceAggregate.ReserveFunds(Id.of(transactionId), amount, _))
        .mapTo[FundsReservationConfirmation]
        .map(confirmation => {
          confirmation.error.map(Left.apply)
            .getOrElse(Right(SagaResult(confirmation.transferId.toString)))
        })
    }

    override def doCommit(transactionId: String): ParticipantEffect[iMadzError, String] = {
      fromAccountRef.ask(CreditBalanceAggregate.DeductFunds(Id.of(transactionId), _))
        .mapTo[FundsDeductionConfirmation]
        .map(confirmation => {
          confirmation.error.map(Left.apply)
            .getOrElse(Right(SagaResult(confirmation.transferId.toString)))
        })
    }

    override def doCompensate(transactionId: String): ParticipantEffect[iMadzError, String] = {
      fromAccountRef.ask(CreditBalanceAggregate.ReleaseReservedFunds(Id.of(transactionId), _))
        .mapTo[FundsReleaseConfirmation]
        .map(confirmation => {
          confirmation.error.map(Left.apply)
            .getOrElse(Right(SagaResult(confirmation.transferId.toString)))
        })
    }

    override protected def customClassification: PartialFunction[Throwable, SagaParticipant.RetryableOrNotException] = {
      case iMadzError("60003", message) => NonRetryableFailure(message)
      case iMadzError("60004", message) => NonRetryableFailure(message)
      case iMadzError(code, message) => NonRetryableFailure(message)
    }
  }

  case class ToAccountParticipant(toUserId: Id, amount: Money, repo: CreditBalanceRepository)(implicit ec: ExecutionContext) extends SagaParticipant[iMadzError, String] {
    private val toAccountRef = repo.findCreditBalanceByUserId(toUserId)

    implicit val timeout: Timeout = 5.seconds

    override def doPrepare(transactionId: String): ParticipantEffect[iMadzError, String] = {
      toAccountRef.ask(CreditBalanceAggregate.RecordIncomingCredits(Id.of(transactionId), amount, _))
        .mapTo[RecordIncomingCreditsConfirmation]
        .map(confirmation => {
          confirmation.error.map(Left.apply)
            .getOrElse(Right(SagaResult(confirmation.transferId.toString)))
        })
    }

    override def doCommit(transactionId: String): ParticipantEffect[iMadzError, String] = {
      toAccountRef.ask(CreditBalanceAggregate.CommitIncomingCredits(Id.of(transactionId), _))
        .mapTo[CommitIncomingCreditsConfirmation]
        .map(confirmation => {
          confirmation.error.map(Left.apply)
            .getOrElse(Right(SagaResult(confirmation.transferId.toString)))
        })
    }

    override def doCompensate(transactionId: String): ParticipantEffect[iMadzError, String] = {
      toAccountRef.ask(CreditBalanceAggregate.CancelIncomingCredit(Id.of(transactionId), _))
        .mapTo[CancelIncomingCreditConfirmation]
        .map(confirmation => {
          confirmation.error.map(Left.apply)
            .getOrElse(Right(SagaResult(confirmation.transferId.toString)))
        })
    }

    override protected def customClassification: PartialFunction[Throwable, SagaParticipant.RetryableOrNotException] = {
      case iMadzError("60003", message) => NonRetryableFailure(message)
      case iMadzError("60004", message) => NonRetryableFailure(message)
      case iMadzError(code, message) => NonRetryableFailure(message)
    }
  }


}