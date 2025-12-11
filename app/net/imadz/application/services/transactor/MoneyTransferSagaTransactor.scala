package net.imadz.application.services.transactor

import akka.actor.typed.ActorRef
import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.common.CborSerializable
import net.imadz.common.CommonTypes.{Id, iMadzError}
import net.imadz.domain.values.Money
import net.imadz.infra.saga.SagaPhase.{CommitPhase, CompensatePhase, PreparePhase}
import net.imadz.infra.saga.SagaTransactionCoordinator.{TracingStep, TransactionResult}
import net.imadz.infra.saga.SagaTransactionStep
import play.api.libs.json.{Json, OWrites}

import scala.concurrent.ExecutionContext

object MoneyTransferSagaTransactor {
  // Commands
  sealed trait MoneyTransferTransactionCommand

  case class InitiateMoneyTransferTransaction(fromUserId: Id, toUserId: Id, amount: Money, replyTo: ActorRef[TransactionResultConfirmation]) extends MoneyTransferTransactionCommand

  case class UpdateMoneyTransferTransactionStatus(id: Id, newStatus: TransactionResult, replyTo: ActorRef[TransactionResultConfirmation]) extends MoneyTransferTransactionCommand

  // Command Replies
  case class TransactionResultConfirmation(transactionId: Id, error: Option[String], tracing: List[TracingStep]) extends CborSerializable

  object TransactionResultConfirmation {
    implicit val confirmationWrites: OWrites[TransactionResultConfirmation] = Json.writes[TransactionResultConfirmation]
  }

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


}