package net.imadz.application.services.transactor


import akka.util.Timeout
import net.imadz.application.aggregates.CreditBalanceProtocol._
import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.common.CommonTypes.{Id, iMadzError}
import net.imadz.common.Id
import net.imadz.domain.values.Money
import net.imadz.infra.saga.SagaParticipant
import net.imadz.infra.saga.SagaParticipant.{NonRetryableFailure, ParticipantEffect, SagaResult}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

case class ToAccountParticipant(toUserId: Id, amount: Money, repo: CreditBalanceRepository)(implicit ec: ExecutionContext) extends SagaParticipant[iMadzError, String] {
  private val toAccountRef = repo.findCreditBalanceByUserId(toUserId)

  implicit val timeout: Timeout = 5.seconds

  override def doPrepare(transactionId: String): ParticipantEffect[iMadzError, String] = {
    toAccountRef.ask(RecordIncomingCredits(Id.of(transactionId), amount, _))
      .mapTo[RecordIncomingCreditsConfirmation]
      .map(confirmation => {
        confirmation.error.map(Left.apply)
          .getOrElse(Right(SagaResult(confirmation.transferId.toString)))
      })
  }

  override def doCommit(transactionId: String): ParticipantEffect[iMadzError, String] = {
    toAccountRef.ask(CommitIncomingCredits(Id.of(transactionId), _))
      .mapTo[CommitIncomingCreditsConfirmation]
      .map(confirmation => {
        confirmation.error.map(Left.apply)
          .getOrElse(Right(SagaResult(confirmation.transferId.toString)))
      })
  }

  override def doCompensate(transactionId: String): ParticipantEffect[iMadzError, String] = {
    toAccountRef.ask(CancelIncomingCredit(Id.of(transactionId), _))
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