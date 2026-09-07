package net.imadz.application.services.transactor

import akka.util.Timeout
import net.imadz.application.aggregates.LotProtocol._
import net.imadz.common.CommonTypes.{Id, iMadzError}
import net.imadz.common.Id
import net.imadz.infra.saga.SagaParticipant
import net.imadz.infra.saga.SagaParticipant.{NonRetryableFailure, ParticipantEffect, SagaResult}

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._

case class TargetLotParticipant(targetLotId: Id, sourceLotId: Id, waferIds: Set[Id])(implicit ec: ExecutionContext)
  extends SagaParticipant[iMadzError, String, AppSagaContext] {

  implicit val timeout: Timeout = 5.seconds

  override def doPrepare(transactionId: String, context: AppSagaContext, traceId: String): ParticipantEffect[iMadzError, String] = {
    val lotRef = context.lots.findLotById(targetLotId)
    fetchCarriedStates(context).flatMap { carried =>
      lotRef.ask(ReserveAddWafer(Id.of(transactionId), waferIds, _, carried))
        .mapTo[WaferAdditionConfirmation]
        .map(c => c.error.map[Either[iMadzError, SagaResult[String]]](Left.apply)
          .getOrElse(Right(SagaResult(c.transferId.toString))))
    }
  }

  /** Snapshot wafer states from the source lot BEFORE any commit mutates it, so the
    * target can restore classification/measurement history at commit (recovery-safe:
    * the snapshot is persisted in WaferAdditionReserved in the target's own journal). */
  private def fetchCarriedStates(context: AppSagaContext): Future[Map[Id, net.imadz.domain.entities.LotEntity.WaferState]] = {
    val sourceRef = context.lots.findLotById(sourceLotId)
    sourceRef.ask(GetLotState(_)).mapTo[LotConfirmation]
      .map(conf => conf.waferStates.filterKeys(waferIds.contains).toMap)
      .recover { case _ => Map.empty[Id, net.imadz.domain.entities.LotEntity.WaferState] }
  }

  override def doCommit(transactionId: String, context: AppSagaContext, traceId: String): ParticipantEffect[iMadzError, String] = {
    val lotRef = context.lots.findLotById(targetLotId)
    lotRef.ask(CommitAddWafer(Id.of(transactionId), _))
      .mapTo[WaferAdditionConfirmation]
      .map(c => c.error.map[Either[iMadzError, SagaResult[String]]](Left.apply)
        .getOrElse(Right(SagaResult(c.transferId.toString))))
  }

  override def doCompensate(transactionId: String, context: AppSagaContext, traceId: String): ParticipantEffect[iMadzError, String] = {
    val lotRef = context.lots.findLotById(targetLotId)
    lotRef.ask(CancelAddWafer(Id.of(transactionId), _))
      .mapTo[WaferAdditionConfirmation]
      .map(c => c.error.map[Either[iMadzError, SagaResult[String]]](Left.apply)
        .getOrElse(Right(SagaResult(c.transferId.toString))))
  }

  override protected def customClassification: PartialFunction[Throwable, SagaParticipant.RetryableOrNotException] = {
    case e: iMadzError =>
      logger.warn(s"[LotInvariant] target-lot reject: ${e.code}: ${e.message}")
      NonRetryableFailure(s"Lot invariant violation (${e.code}: ${e.message})")
  }
}
