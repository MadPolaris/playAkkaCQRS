package net.imadz.application.services.transactor


import akka.util.Timeout
import net.imadz.application.aggregates.CreditBalanceAggregate
import net.imadz.application.aggregates.CreditBalanceAggregate._
import net.imadz.common.CommonTypes.{Id, iMadzError}
import net.imadz.common.Id
import net.imadz.domain.values.Money
import net.imadz.infra.saga.SagaParticipant
import net.imadz.infra.saga.SagaParticipant.{NonRetryableFailure, ParticipantEffect, SagaResult}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

case class ToAccountParticipant(toUserId: Id, amount: Money)(implicit ec: ExecutionContext) extends SagaParticipant[iMadzError, String, MoneyTransferContext] {

  implicit val timeout: Timeout = 5.seconds

  override protected def doPrepare(transactionId: String, context: MoneyTransferContext): ParticipantEffect[iMadzError, String] = {
    val toAccountRef = context.repository.findCreditBalanceByUserId(toUserId)

    toAccountRef.ask(CreditBalanceAggregate.RecordIncomingCredits(Id.of(transactionId), amount, _))
      .mapTo[RecordIncomingCreditsConfirmation]
      .map(confirmation => {
        confirmation.error.map[Either[iMadzError, SagaResult[String]]](Left.apply)
          .getOrElse(Right(SagaResult[String](confirmation.transferId.toString)))
      })
  }


  override protected def doCommit(transactionId: String, context: MoneyTransferContext): ParticipantEffect[iMadzError, String] = {
    val toAccountRef = context.repository.findCreditBalanceByUserId(toUserId)

    toAccountRef.ask(CreditBalanceAggregate.CommitIncomingCredits(Id.of(transactionId), _))
      .mapTo[CommitIncomingCreditsConfirmation]
      .map(confirmation => {
        confirmation.error.map[Either[iMadzError, SagaResult[String]]](Left.apply)
          .getOrElse(Right(SagaResult(confirmation.transferId.toString)))
      })
  }

  override def doCompensate(transactionId: String, context: MoneyTransferContext): ParticipantEffect[iMadzError, String] = {
    val toAccountRef = context.repository.findCreditBalanceByUserId(toUserId)

    toAccountRef.ask(CreditBalanceAggregate.CancelIncomingCredit(Id.of(transactionId), _))
      .mapTo[CancelIncomingCreditConfirmation]
      .map(confirmation => {
        confirmation.error.map[Either[iMadzError, SagaResult[String]]](Left.apply)
          .getOrElse(Right(SagaResult(confirmation.transferId.toString)))
      })
  }

  override protected def customClassification: PartialFunction[Throwable, SagaParticipant.RetryableOrNotException] = {
    case iMadzError("60003", message) => NonRetryableFailure(message)
    case iMadzError("60004", message) => NonRetryableFailure(message)
    case iMadzError(code, message) => NonRetryableFailure(message)
  }


}