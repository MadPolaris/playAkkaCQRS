package net.imadz.application.services.transactor

import akka.util.Timeout
import net.imadz.application.aggregates.WaferProtocol._
import net.imadz.common.CommonTypes.{Id, iMadzError}
import net.imadz.common.Id
import net.imadz.infra.saga.SagaParticipant
import net.imadz.infra.saga.SagaParticipant.{NonRetryableFailure, ParticipantEffect, SagaResult}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

case class WaferTransferParticipant(waferId: Id, targetLotId: Id)(implicit ec: ExecutionContext)
  extends SagaParticipant[iMadzError, String, FabTransactionContext] {

  implicit val timeout: Timeout = 5.seconds

  override def doPrepare(transactionId: String, context: FabTransactionContext, traceId: String): ParticipantEffect[iMadzError, String] = {
    val waferRef = context.waferRepository.findWaferById(waferId)
    waferRef.ask(ReserveTransfer(Id.of(transactionId), targetLotId, _))
      .mapTo[TransferConfirmation]
      .map(c => c.error.map[Either[iMadzError, SagaResult[String]]](Left.apply)
        .getOrElse(Right(SagaResult(c.transferId.toString))))
  }

  override def doCommit(transactionId: String, context: FabTransactionContext, traceId: String): ParticipantEffect[iMadzError, String] = {
    val waferRef = context.waferRepository.findWaferById(waferId)
    waferRef.ask(CommitTransfer(Id.of(transactionId), targetLotId, _))
      .mapTo[TransferConfirmation]
      .map(c => c.error.map[Either[iMadzError, SagaResult[String]]](Left.apply)
        .getOrElse(Right(SagaResult(c.transferId.toString))))
  }

  override def doCompensate(transactionId: String, context: FabTransactionContext, traceId: String): ParticipantEffect[iMadzError, String] = {
    val waferRef = context.waferRepository.findWaferById(waferId)
    waferRef.ask(ReleaseTransfer(Id.of(transactionId), _))
      .mapTo[TransferConfirmation]
      .map(c => c.error.map[Either[iMadzError, SagaResult[String]]](Left.apply)
        .getOrElse(Right(SagaResult(c.transferId.toString))))
  }

  override protected def customClassification: PartialFunction[Throwable, SagaParticipant.RetryableOrNotException] = {
    case _: iMadzError => NonRetryableFailure("Wafer invariant violation")
  }
}
