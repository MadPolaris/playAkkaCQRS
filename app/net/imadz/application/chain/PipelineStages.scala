package net.imadz.application.chain

import net.imadz.application.aggregates.LotProtocol.{LotCommand, LotConfirmation, SealLot}
import net.imadz.application.aggregates.LotProtocol.LotCommand
import net.imadz.application.aggregates.LotProtocol._
import net.imadz.application.services.transactor.FabSagaProtocol.FabSagaConfirmation
import net.imadz.common.CommonTypes.Id
import net.imadz.domain.events._
import net.imadz.application.chain.FabExecutionModel.{FabDemoContext, FabDemoState, StageError, StageFailedException, WaferInfo}
import net.imadz.fab.protocol._
import net.imadz.application.scenario.{DecisionConfig, FabSimulationScenario}
import org.slf4j.LoggerFactory

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

/**
 * Shared stage functions used by PorEngine, FabDemoPipeline, FabScenarioPipeline,
 * and FabFlowEngine.
 *
 * Each stage is a pure function `(FabDemoState, FabDemoContext) => Future[FabDemoState]`.
 * Equipment and Saga interactions are async; WebSocket events are published inline.
 */
object PipelineStages {

  private val logger = LoggerFactory.getLogger("PipelineStages")

  // ====================================================================
  // Stage 1: Load FOUP
  // ====================================================================
  def loadFoup(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    emitLedger(state, "PhaseLoad: Load FOUP from Stocker", ctx)
    ctx.stageProgress("LOADING", "Loading FOUP", "PhaseLoad")
    ctx.lotRef ! RecordFoupLoaded(ctx.foupId, ctx.scenario.stocker.equipmentId, ctx.ignoreLotReply)
    ctx.publisher(FoupStateChanged(ctx.foupId, "LOADING", activeCount(state), 0, "STOCKER", lotId = ctx.scenario.scenarioId))
    ctx.publisher(FoupArrivedAtPort(ctx.foupId, ctx.scenario.stocker.equipmentId, "STOCKER-PORT-1"))
    Future.successful(state.copy(ledgerSeq = state.ledgerSeq + 1, currentArea = "STOCKER"))
  }

  // ====================================================================
  // Stage 2+: AMHS Transport
  // ====================================================================
  def transport(state: FabDemoState, ctx: FabDemoContext, from: String, to: String): Future[FabDemoState] = {
    val s = emitLedger(state, s"PhaseTransport: $from → $to", ctx)
    ctx.stageProgress("TRANSPORTING", s"$from → $to", "PhaseTransport")
    val routeMs = ctx.scenario.amhs.routes.get((from, to)).map(_.toMillis).getOrElse(2000L)
    val scaledMs = (routeMs / ctx.speedMultiplier).toLong
    ctx.lotRef ! RecordTransportStarted(ctx.foupId, from, to, scaledMs, ctx.ignoreLotReply)
    ctx.publisher(FoupInTransit(ctx.foupId, from, to, scaledMs / 2))
    ctx.publisher(FoupStateChanged(ctx.foupId, "IN_TRANSIT", activeCount(state), 0, "AMHS", lotId = ctx.scenario.scenarioId))
        val areaTransit = s"$from → $to"
    ctx.adapter.sendCommand("AMHS", TransferFoup(ctx.foupId, from, to)).map { _ =>
      s.copy(ledgerSeq = s.ledgerSeq + 1, currentArea = areaTransit)
    }(ctx.ec)
  }

  // ====================================================================
  // Stage: TrackIn (进站) — FOUP loaded to equipment load port
  // ====================================================================
  def trackIn(state: FabDemoState, ctx: FabDemoContext, equipId: String, portId: String = "LP1"): Future[FabDemoState] = {
    val s = emitLedger(state, s"PhaseTrackIn: $equipId port $portId", ctx)
    ctx.stageProgress("TRACK_IN", s"$equipId:$portId loading", "PhaseTrackIn")
    ctx.publisher(FoupArrivedAtPort(ctx.foupId, equipId, portId))
    val areaType = if (equipId.contains("LITHO")) "LITHO" else if (equipId.contains("CDSEM")) "METROLOGY" else equipId
    ctx.publisher(EquipmentStateChanged(equipId, areaType, "Load", Some(s"lot-${s.ledgerSeq}")))
    ctx.publisher(FoupStateChanged(ctx.foupId, "ON_PORT", activeCount(state), 0, equipId, lotId = ctx.scenario.scenarioId))
    Future.successful(s.copy(ledgerSeq = s.ledgerSeq + 1, currentArea = equipId))
  }

  // ====================================================================
  // Stage: At Equipment (arrival)
  // ====================================================================
  def atEquipment(state: FabDemoState, ctx: FabDemoContext, area: String, equipId: String): Future[FabDemoState] = {
    val s = emitLedger(state, s"PhaseAt$area: FOUP at $area", ctx)
    ctx.stageProgress("AT_EQP", s"FOUP at $area", s"PhaseAt$area")
    ctx.lotRef ! RecordTransportCompleted(ctx.foupId, equipId, ctx.ignoreLotReply)
    ctx.publisher(FoupArrivedAtPort(ctx.foupId, equipId, s"$equipId-PORT-1"))
    ctx.publisher(FoupStateChanged(ctx.foupId, "AT_EQUIPMENT", activeCount(state), 0, area, lotId = ctx.scenario.scenarioId))
    val areaType = if (area == "LITHO") "LITHO" else if (area == "MET" || area == "CDSEM") "METROLOGY" else area
    ctx.publisher(EquipmentStateChanged(equipId, areaType, "Idle", None))
    val newState = s.copy(ledgerSeq = s.ledgerSeq + 1, currentArea = area)
    Future.successful(newState)
  }

  // ====================================================================
  // Stage: Process (generic recipe execution)
  // ====================================================================
  def process(state: FabDemoState, ctx: FabDemoContext, equipId: String, recipeId: String, areaType: String): Future[FabDemoState] = {
    val s = emitLedger(state, s"PhaseProcess: $recipeId on $equipId", ctx)
    ctx.stageProgress("PROCESSING", s"$equipId processing", "PhaseProcess")
    val scaledMs = (ctx.scenario.litho.processingTime.toMillis / ctx.speedMultiplier).toLong
    ctx.lotRef ! RecordEquipmentJobStarted(equipId, recipeId, ctx.ignoreLotReply)
    ctx.publisher(EquipmentStateChanged(equipId, areaType, "Busy", Some(s"job-$recipeId")))
    ctx.publisher(ProcessingStarted(equipId, recipeId, scaledMs))
    ctx.adapter.sendCommand(equipId, ProcessRecipe(recipeId)).flatMap {
      case JobCompleted(jobId, _, _) =>
        ctx.lotRef ! RecordEquipmentJobCompleted(equipId, jobId, success = true, ctx.ignoreLotReply)
        ctx.publisher(ProcessingCompleted(equipId, jobId, success = true, ""))
        ctx.publisher(EquipmentStateChanged(equipId, areaType, "Idle", None))
        Future.successful(s.copy(ledgerSeq = s.ledgerSeq + 1))
      case JobFailed(jobId, _, errorCode, detail) =>
        val err = StageError("Process", Some(equipId), errorCode, detail)
        ctx.publisher(net.imadz.domain.events.PipelineStageFailed(err.stageName, err.equipId, err.errorCode, err.detail))
        ctx.publisher(ProcessingCompleted(equipId, jobId, success = false, detail))
        ctx.publisher(EquipmentStateChanged(equipId, areaType, "Idle", None))
        Future.failed(StageFailedException(err))
      case _ => Future.successful(s.copy(ledgerSeq = s.ledgerSeq + 1))
    }(ctx.ec)
  }

  // ====================================================================
  // Stage: TrackOut (出站) — FOUP unloaded from equipment after processing
  // ====================================================================
  def trackOut(state: FabDemoState, ctx: FabDemoContext, equipId: String, portId: String = "LP1"): Future[FabDemoState] = {
    val s = emitLedger(state, s"PhaseTrackOut: $equipId port $portId", ctx)
    ctx.stageProgress("TRACK_OUT", s"$equipId:$portId unloading", "PhaseTrackOut")
    val areaType = if (equipId.contains("LITHO")) "LITHO" else if (equipId.contains("CDSEM")) "METROLOGY" else equipId
    ctx.publisher(EquipmentStateChanged(equipId, areaType, "Idle", None))
    ctx.publisher(FoupStateChanged(ctx.foupId, "UNLOADED", activeCount(state), 0, equipId, lotId = ctx.scenario.scenarioId))
    Future.successful(s.copy(ledgerSeq = s.ledgerSeq + 1))
  }

  // ====================================================================
  // Stage: CD-SEM Measure
  // ====================================================================
  def measure(state: FabDemoState, ctx: FabDemoContext, equipId: String): Future[FabDemoState] = {
    val s = emitLedger(state, "PhaseMeasure: CD-SEM", ctx)
    ctx.stageProgress("MEASURING", "CD measurement", "PhaseMeasure")
    val scaledMs = (ctx.scenario.cdSem.processingTime.toMillis / ctx.speedMultiplier).toLong
    ctx.lotRef ! RecordEquipmentJobStarted(equipId, "CD-MEASURE-001", ctx.ignoreLotReply)
    ctx.publisher(EquipmentStateChanged(equipId, "METROLOGY", "Busy", Some("metrology-job")))
    ctx.publisher(ProcessingStarted(equipId, "CD-MEASURE-001", scaledMs))
    ctx.adapter.sendCommand(equipId, ProcessRecipe("CD-MEASURE-001")).flatMap {
      case JobCompleted(jobId, _, MetrologyResult(_, waferMeasurements)) =>
        ctx.lotRef ! RecordEquipmentJobCompleted(equipId, jobId, success = true, ctx.ignoreLotReply)
        ctx.publisher(ProcessingCompleted(equipId, jobId, success = true, ""))
        ctx.publisher(EquipmentStateChanged(equipId, "METROLOGY", "Idle", None))
        val cdValues: Map[String, Double] = waferMeasurements.map { case (wid, cd) => wid -> cd.measuredNm }
        logger.info(s"[Measure] CDSEM returned ${cdValues.size} wafer CD values: ${cdValues.map { case (k, v) => s"$k=$v" }.mkString(", ")}")
        val newWafers = s.wafers.map { case (wid, info) =>
          val cdVal = cdValues.get(wid)
          if (cdVal.isEmpty) logger.warn(s"[Measure] No CD value for wafer $wid in CDSEM response! Available: ${cdValues.keys.mkString(", ")}")
          wid -> info.copy(cdValueHistory = info.cdValueHistory ++ cdVal.toList)
        }
        Future.successful(s.copy(ledgerSeq = s.ledgerSeq + 1, wafers = newWafers))
      case JobFailed(jobId, _, errorCode, detail) =>
        val err = StageError("Measure", Some(equipId), errorCode, detail)
        ctx.publisher(net.imadz.domain.events.PipelineStageFailed(err.stageName, err.equipId, err.errorCode, err.detail))
        ctx.publisher(ProcessingCompleted(equipId, jobId, success = false, detail))
        ctx.publisher(EquipmentStateChanged(equipId, "METROLOGY", "Idle", None))
        Future.failed(StageFailedException(err))
      case other =>
        logger.warn(s"[Measure] Unexpected CDSEM response (expected MetrologyResult): ${other.getClass.getSimpleName}")
        Future.successful(s.copy(ledgerSeq = s.ledgerSeq + 1))
    }(ctx.ec)
  }

  // ====================================================================
  // Stage: Classify (static CD threshold — used by FabDemoPipeline/FabScenarioPipeline)
  // ====================================================================
  def classifyCd(cdValue: Double, config: DecisionConfig): String = {
    if (cdValue >= config.lowerSpecNm && cdValue <= config.upperSpecNm) "PASS"
    else if (cdValue > config.upperSpecNm && cdValue <= config.upperSpecNm + config.borderlineWindowNm) "BORDERLINE"
    else if (cdValue > config.upperSpecNm + 8.0) "SCRAP"
    else "FAIL"
  }

  // ====================================================================
  // Final Stage: Seal + Complete
  // ====================================================================
  def sealComplete(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    val s = emitLedger(state, "PhaseComplete: Lot sealed", ctx)
    ctx.stageProgress("COMPLETED", "Demo completed", "PhaseComplete")
    ctx.lotRef ! RecordTransportCompleted(ctx.foupId, ctx.scenario.stocker.equipmentId, ctx.ignoreLotReply)
    ctx.lotRef ! SealLot(ctx.ignoreLotReply)
    val totalRework = state.wafers.values.count(_.reworkCount > 0)
    ctx.lotRef ! CompleteProcess(ctx.scenario.scenarioId, state.passCount, state.scrapCount, totalRework, ctx.ignoreLotReply)
    ctx.publisher(FoupStateChanged(ctx.foupId, "COMPLETED", 0, 0, "STOCKER", lotId = ctx.scenario.scenarioId))
    ctx.publisher(FoupArrivedAtPort(ctx.foupId, ctx.scenario.stocker.equipmentId, "STOCKER-PORT-1"))
    ctx.publisher(LotUpdated(ctx.scenario.scenarioId, ctx.scenario.lotSize, state.scrapCount,
      (1 to state.iteration + 1).map(i => s"Completed-$i").toList, state.passCount, totalRework))
    ctx.publisher(RecoveryCompleted(ctx.scenario.scenarioId, ctx.scenario.lotSize, state.passCount, totalRework, state.scrapCount))
    Future.successful(s.copy(ledgerSeq = s.ledgerSeq + 1, currentArea = "STOCKER"))
  }

  // ====================================================================
  // Helpers
  // ====================================================================
  def activeCount(state: FabDemoState): Int =
    state.wafers.values.count(w => w.classification.isEmpty || w.classification.contains("FAIL"))

  def reworkCount(state: FabDemoState): Int =
    state.wafers.values.count(w => w.classification.contains("FAIL") && w.reworkCount > 0)

  def unresolvedIds(state: FabDemoState): Seq[String] =
    state.wafers.values.filter(w => w.classification.isEmpty || w.classification.contains("FAIL")).map(_.waferId).toSeq

  def cmdId(): String = UUID.randomUUID().toString.take(8)

  def scale(d: FiniteDuration, multiplier: Double): FiniteDuration =
    if (multiplier > 0) (d.toMillis / multiplier).toLong.millis else d

  def emitLedger(state: FabDemoState, name: String, ctx: FabDemoContext,
                 nodeId: Option[String] = None, subProcess: Option[String] = None,
                 branchDecision: Option[String] = None): FabDemoState = {
    ctx.publisher(LedgerStepAdvanced(state.ledgerSeq, name, nodeId, subProcess, branchDecision))
    state
  }

}
