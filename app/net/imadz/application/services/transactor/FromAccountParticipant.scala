package net.imadz.application.services.transactor


import akka.util.Timeout
import net.imadz.application.aggregates.CreditBalanceAggregate
import net.imadz.application.aggregates.CreditBalanceAggregate._
import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.common.CommonTypes.{Id, iMadzError}
import net.imadz.common.Id
import net.imadz.domain.values.Money
import net.imadz.infra.saga.SagaParticipant
import net.imadz.infra.saga.SagaParticipant.{NonRetryableFailure, ParticipantEffect, SagaResult}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

case class FromAccountParticipant(fromUserId: Id, amount: Money)(implicit ec: ExecutionContext) extends SagaParticipant[iMadzError, String, CreditBalanceRepository] {

  implicit val timeout: Timeout = 5.seconds

  override def doPrepare(transactionId: String, repo: CreditBalanceRepository): ParticipantEffect[iMadzError, String] = {
    val fromAccountRef = repo.findCreditBalanceByUserId(fromUserId)
    fromAccountRef.ask(CreditBalanceAggregate.ReserveFunds(Id.of(transactionId), amount, _))
      .mapTo[FundsReservationConfirmation]
      .map(confirmation => {
        confirmation.error.map[Either[iMadzError, SagaResult[String]]](Left.apply)
          .getOrElse(Right(SagaResult[String](confirmation.transferId.toString)))
      })
  }

  override def doCommit(transactionId: String, repo: CreditBalanceRepository): ParticipantEffect[iMadzError, String] = {
    val fromAccountRef = repo.findCreditBalanceByUserId(fromUserId)

    fromAccountRef.ask(CreditBalanceAggregate.DeductFunds(Id.of(transactionId), _))
      .mapTo[FundsDeductionConfirmation]
      .map(confirmation => {
        confirmation.error.map[Either[iMadzError, SagaResult[String]]](Left.apply)
          .getOrElse(Right(SagaResult[String](confirmation.transferId.toString)))
      })
  }

  override def doCompensate(transactionId: String, repo: CreditBalanceRepository): ParticipantEffect[iMadzError, String] = {
    val fromAccountRef = repo.findCreditBalanceByUserId(fromUserId)
    fromAccountRef.ask(CreditBalanceAggregate.ReleaseReservedFunds(Id.of(transactionId), _))
      .mapTo[FundsReleaseConfirmation]
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