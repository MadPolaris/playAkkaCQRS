package net.imadz.application.services.transactor

import akka.util.Timeout
import net.imadz.application.aggregates.LotProtocol._
import net.imadz.common.CommonTypes.{Id, iMadzError}
import net.imadz.common.Id
import net.imadz.infra.saga.SagaParticipant
import net.imadz.infra.saga.SagaParticipant.{NonRetryableFailure, ParticipantEffect, SagaResult}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

case class TargetLotParticipant(targetLotId: Id, waferIds: Set[Id])(implicit ec: ExecutionContext)
  extends SagaParticipant[iMadzError, String, FabTransactionContext] {

  implicit val timeout: Timeout = 5.seconds

  override def doPrepare(transactionId: String, context: FabTransactionContext, traceId: String): ParticipantEffect[iMadzError, String] = {
    val lotRef = context.lotRepository.findLotById(targetLotId)
    lotRef.ask(ReserveAddWafer(Id.of(transactionId), waferIds, _))
      .mapTo[WaferAdditionConfirmation]
      .map(c => c.error.map[Either[iMadzError, SagaResult[String]]](Left.apply)
        .getOrElse(Right(SagaResult(c.transferId.toString))))
  }

  override def doCommit(transactionId: String, context: FabTransactionContext, traceId: String): ParticipantEffect[iMadzError, String] = {
    val lotRef = context.lotRepository.findLotById(targetLotId)
    lotRef.ask(CommitAddWafer(Id.of(transactionId), _))
      .mapTo[WaferAdditionConfirmation]
      .map(c => c.error.map[Either[iMadzError, SagaResult[String]]](Left.apply)
        .getOrElse(Right(SagaResult(c.transferId.toString))))
  }

  override def doCompensate(transactionId: String, context: FabTransactionContext, traceId: String): ParticipantEffect[iMadzError, String] = {
    val lotRef = context.lotRepository.findLotById(targetLotId)
    lotRef.ask(CancelAddWafer(Id.of(transactionId), _))
      .mapTo[WaferAdditionConfirmation]
      .map(c => c.error.map[Either[iMadzError, SagaResult[String]]](Left.apply)
        .getOrElse(Right(SagaResult(c.transferId.toString))))
  }

  override protected def customClassification: PartialFunction[Throwable, SagaParticipant.RetryableOrNotException] = {
    case _: iMadzError => NonRetryableFailure("Lot invariant violation")
  }
}
