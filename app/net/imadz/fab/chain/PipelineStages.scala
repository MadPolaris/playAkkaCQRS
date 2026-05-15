package net.imadz.fab.chain

import net.imadz.application.aggregates.LotProtocol.{LotCommand, LotConfirmation, SealLot}
import net.imadz.application.aggregates.LotProtocol.LotCommand
import net.imadz.application.aggregates.LotProtocol._
import net.imadz.application.services.transactor.FabSagaProtocol.FabSagaConfirmation
import net.imadz.common.CommonTypes.Id
import net.imadz.fab.events._
import net.imadz.fab.model.FabExecutionModel.{FabDemoContext, FabDemoState, WaferInfo}
import net.imadz.fab.protocol._
import net.imadz.fab.scenario.{DecisionConfig, FabSimulationScenario}

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

  // ====================================================================
  // Stage 1: Load FOUP
  // ====================================================================
  def loadFoup(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    emitLedger(state, "PhaseLoad: Load FOUP from Stocker", ctx)
    ctx.publisher(GlobalStatusChanged("LOADING", "Loading FOUP", "PhaseLoad"))
    ctx.lotRef ! RecordFoupLoaded(ctx.foupId, ctx.scenario.stocker.equipmentId, ctx.ignoreLotReply)
    ctx.publisher(FoupStateChanged(ctx.foupId, "LOADING", activeCount(state), 0, "STOCKER", lotId = ctx.scenario.scenarioId))
    ctx.publisher(FoupArrivedAtPort(ctx.foupId, ctx.scenario.stocker.equipmentId, "STOCKER-PORT-1"))
    ctx.publisher(AggregateStateUpdated(
      LotStateSnapshot(ctx.scenario.scenarioId, "Active", ctx.scenario.lotSize, state.passCount, state.scrapCount, "STOCKER"),
      Seq.empty,
      state.wafers.map { case (wid, info) =>
        WaferStateSnapshot(wid, "Active", ctx.scenario.scenarioId, info.classification.getOrElse("Pending"), 0)
      }.toSeq
    ))
    Future.successful(state.copy(ledgerSeq = state.ledgerSeq + 1, currentArea = "STOCKER"))
  }

  // ====================================================================
  // Stage 2+: AMHS Transport
  // ====================================================================
  def transport(state: FabDemoState, ctx: FabDemoContext, from: String, to: String): Future[FabDemoState] = {
    val s = emitLedger(state, s"PhaseTransport: $from → $to", ctx)
    ctx.publisher(GlobalStatusChanged("TRANSPORTING", s"$from → $to", "PhaseTransport"))
    val routeMs = ctx.scenario.amhs.routes.get((from, to)).map(_.toMillis).getOrElse(2000L)
    val scaledMs = (routeMs / ctx.speedMultiplier).toLong
    ctx.lotRef ! RecordTransportStarted(ctx.foupId, from, to, scaledMs, ctx.ignoreLotReply)
    ctx.publisher(FoupInTransit(ctx.foupId, from, to, scaledMs / 2))
    ctx.publisher(FoupStateChanged(ctx.foupId, "IN_TRANSIT", activeCount(state), 0, "AMHS", lotId = ctx.scenario.scenarioId))
        val areaTransit = s"$from → $to"
    ctx.adapter.sendCommand("AMHS", TransferFoup(ctx.foupId, from, to)).map { _ =>
      val ns = s.copy(ledgerSeq = s.ledgerSeq + 1, currentArea = areaTransit)
      ctx.publisher(buildAggregateState(ns.wafers, ctx, ns.passCount, ns.scrapCount, sourceLotArea = areaTransit, childLotView = ns.childLotView))
      ns
    }(ctx.ec)
  }

  // ====================================================================
  // Stage: At Equipment (arrival)
  // ====================================================================
  def atEquipment(state: FabDemoState, ctx: FabDemoContext, area: String, equipId: String): Future[FabDemoState] = {
    val s = emitLedger(state, s"PhaseAt$area: FOUP at $area", ctx)
    ctx.publisher(GlobalStatusChanged("AT_EQP", s"FOUP at $area", s"PhaseAt$area"))
    ctx.lotRef ! RecordTransportCompleted(ctx.foupId, equipId, ctx.ignoreLotReply)
    ctx.publisher(FoupArrivedAtPort(ctx.foupId, equipId, s"$equipId-PORT-1"))
    ctx.publisher(FoupStateChanged(ctx.foupId, "AT_EQUIPMENT", activeCount(state), 0, area, lotId = ctx.scenario.scenarioId))
    val areaType = if (area == "LITHO") "LITHO" else if (area == "MET" || area == "CDSEM") "METROLOGY" else area
    ctx.publisher(EquipmentStateChanged(equipId, areaType, "Idle", None))
    val newState = s.copy(ledgerSeq = s.ledgerSeq + 1, currentArea = area)
    ctx.publisher(buildAggregateState(state.wafers, ctx, state.passCount, state.scrapCount, sourceLotArea = area, childLotView = state.childLotView))
    Future.successful(newState)
  }

  // ====================================================================
  // Stage: Process (generic recipe execution)
  // ====================================================================
  def process(state: FabDemoState, ctx: FabDemoContext, equipId: String, recipeId: String, areaType: String): Future[FabDemoState] = {
    val s = emitLedger(state, s"PhaseProcess: $recipeId on $equipId", ctx)
    ctx.publisher(GlobalStatusChanged("PROCESSING", s"$equipId processing", "PhaseProcess"))
    val scaledMs = (ctx.scenario.litho.processingTime.toMillis / ctx.speedMultiplier).toLong
    ctx.lotRef ! RecordEquipmentJobStarted(equipId, recipeId, ctx.ignoreLotReply)
    ctx.publisher(EquipmentStateChanged(equipId, areaType, "Busy", Some(s"job-$recipeId")))
    ctx.publisher(ProcessingStarted(equipId, recipeId, scaledMs))
    ctx.adapter.sendCommand(equipId, ProcessRecipe(recipeId)).map {
      case JobCompleted(jobId, _, _) =>
        ctx.lotRef ! RecordEquipmentJobCompleted(equipId, jobId, success = true, ctx.ignoreLotReply)
        ctx.publisher(ProcessingCompleted(equipId, jobId, success = true, ""))
        ctx.publisher(EquipmentStateChanged(equipId, areaType, "Idle", None))
        ctx.publisher(buildAggregateState(s.wafers, ctx, s.passCount, s.scrapCount, sourceLotArea = s.currentArea, childLotView = s.childLotView))
        s.copy(ledgerSeq = s.ledgerSeq + 1)
      case _ => s.copy(ledgerSeq = s.ledgerSeq + 1)
    }(ctx.ec)
  }

  // ====================================================================
  // Stage: CD-SEM Measure
  // ====================================================================
  def measure(state: FabDemoState, ctx: FabDemoContext, equipId: String): Future[FabDemoState] = {
    val s = emitLedger(state, "PhaseMeasure: CD-SEM", ctx)
    ctx.publisher(GlobalStatusChanged("MEASURING", "CD measurement", "PhaseMeasure"))
    val scaledMs = (ctx.scenario.cdSem.processingTime.toMillis / ctx.speedMultiplier).toLong
    ctx.lotRef ! RecordEquipmentJobStarted(equipId, "CD-MEASURE-001", ctx.ignoreLotReply)
    ctx.publisher(EquipmentStateChanged(equipId, "METROLOGY", "Busy", Some("metrology-job")))
    ctx.publisher(ProcessingStarted(equipId, "CD-MEASURE-001", scaledMs))
    ctx.adapter.sendCommand(equipId, ProcessRecipe("CD-MEASURE-001")).map {
      case JobCompleted(jobId, _, MetrologyResult(_, waferMeasurements)) =>
        ctx.lotRef ! RecordEquipmentJobCompleted(equipId, jobId, success = true, ctx.ignoreLotReply)
        ctx.publisher(ProcessingCompleted(equipId, jobId, success = true, ""))
        ctx.publisher(EquipmentStateChanged(equipId, "METROLOGY", "Idle", None))
        val cdValues: Map[String, Double] = waferMeasurements.map { case (wid, cd) => wid -> cd.measuredNm }
        val newWafers = s.wafers.map { case (wid, info) =>
          wid -> info.copy(cdValueHistory = info.cdValueHistory ++ cdValues.get(wid).toList)
        }
        ctx.publisher(buildAggregateState(newWafers, ctx, s.passCount, s.scrapCount, sourceLotArea = s.currentArea, childLotView = s.childLotView))
        s.copy(ledgerSeq = s.ledgerSeq + 1, wafers = newWafers)
      case _ => s.copy(ledgerSeq = s.ledgerSeq + 1)
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
    ctx.publisher(GlobalStatusChanged("COMPLETED", "Demo completed", "PhaseComplete"))
    ctx.lotRef ! RecordTransportCompleted(ctx.foupId, ctx.scenario.stocker.equipmentId, ctx.ignoreLotReply)
    ctx.lotRef ! SealLot(ctx.ignoreLotReply)
    val totalRework = state.wafers.values.count(_.reworkCount > 0)
    ctx.lotRef ! CompleteProcess(ctx.scenario.scenarioId, state.passCount, state.scrapCount, totalRework, ctx.ignoreLotReply)
    ctx.publisher(FoupStateChanged(ctx.foupId, "COMPLETED", 0, 0, "STOCKER", lotId = ctx.scenario.scenarioId))
    ctx.publisher(FoupArrivedAtPort(ctx.foupId, ctx.scenario.stocker.equipmentId, "STOCKER-PORT-1"))
    ctx.publisher(LotUpdated(ctx.scenario.scenarioId, ctx.scenario.lotSize, state.scrapCount,
      (1 to state.iteration + 1).map(i => s"Completed-$i").toList, state.passCount, totalRework))
    ctx.publisher(DemoCompleted(ctx.scenario.scenarioId, ctx.scenario.lotSize, state.passCount, totalRework, state.scrapCount))
    ctx.publisher(buildAggregateState(state.wafers, ctx, state.passCount, state.scrapCount, sourceLotArea = "STOCKER", childLotView = state.childLotView))
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

  def emitLedger(state: FabDemoState, name: String, ctx: FabDemoContext): FabDemoState = {
    ctx.publisher(LedgerStepAdvanced(state.ledgerSeq, name))
    state
  }

  def buildAggregateState(wafers: Map[String, WaferInfo], ctx: FabDemoContext,
                          totalPass: Int, totalScrap: Int,
                          childLotStatuses: Map[String, (String, Int)] = Map.empty,
                          sourceLotArea: String = "",
                          childLotView: Map[String, (String, Int)] = Map.empty): AggregateStateUpdated = {
    val srcLotId = ctx.scenario.scenarioId
    val sourceLot = LotStateSnapshot(srcLotId, "Active", ctx.scenario.lotSize, totalPass, totalScrap, sourceLotArea)

    // Auto-detect child lots from wafers' subLot values
    val detectedChildLots: Map[String, (String, Int)] = wafers.values
      .flatMap(_.subLot)
      .groupBy(identity)
      .map { case (key, infos) => key -> ("Active", infos.size) }
        // childLotView persists after merge (subLot cleared) so child lot shows as Merged/terminal
    val mergedChildLots = detectedChildLots ++ childLotView ++ childLotStatuses
    val childLots = mergedChildLots.map { case (key, (status, count)) =>
      LotStateSnapshot(s"$srcLotId-${key.toUpperCase}", status, count, 0, 0, sourceLotArea)
    }.toList

    val waferSnapshots = wafers.map { case (wid, info) =>
      val waferLot = info.subLot.map(k => s"$srcLotId-${k.toUpperCase}").getOrElse(srcLotId)
      WaferStateSnapshot(wid,
        status = if (info.classification.contains("SCRAP")) "Scrapped"
                 else if (info.classification.contains("HOLD")) "OnHold"
                 else "Active",
        lotId = waferLot, classification = info.classification.getOrElse("Pending"), reworkCount = info.reworkCount)
    }.toSeq
    AggregateStateUpdated(sourceLot, childLots, waferSnapshots)
  }
}
