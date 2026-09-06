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
import net.imadz.application.actor.EquipmentAreaActor
import akka.actor.typed.{ActorRef, Scheduler}
import akka.actor.typed.scaladsl.AskPattern
import akka.util.Timeout
import org.slf4j.LoggerFactory

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
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

  /**
   * 同步等待设备区 Actor 接受迁移（ask）：确认 LOADED/BUSY/FINISHED 等状态真实落定后流水线才继续，
   * 消除"管线旁白 vs 区域状态"的时序错位。区域未初始化（单测）或 ask 超时/被拒时仅告警并继续，不中断演示。
   */
  private def askArea(ctx: FabDemoContext, areaType: String,
                      mk: Option[ActorRef[EquipmentAreaActor.AreaReply]] => EquipmentAreaActor.Command): Future[Boolean] = {
    ctx.areaActorOf(areaType) match {
      case Some(ref) =>
        implicit val scheduler: Scheduler = EquipmentAreaActor.Registry.scheduler
        implicit val timeout: Timeout = 5.seconds
        ref.ask((replyTo: ActorRef[EquipmentAreaActor.AreaReply]) => mk(Some(replyTo)))
          .map { r =>
            if (!r.accepted) logger.warn(s"[Area $areaType] command rejected: ${r.reason} (status=${r.status})")
            r.accepted
          }(ctx.ec)
          .recover { case ex =>
            logger.warn(s"[Area $areaType] ask failed (continuing): ${ex.getMessage}")
            false
          }(ctx.ec)
      case None => Future.successful(true)
    }
  }

  // ====================================================================
  // Stage 1: Load FOUP
  // ====================================================================
  def loadFoup(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    emitLedger(state, "PhaseLoad: Load FOUP from Stocker", ctx)
    ctx.stageProgress("LOADING", "Loading FOUP", "PhaseLoad")
    ctx.lotRef ! RecordFoupLoaded(ctx.foupId, ctx.scenario.stocker.equipmentId, ctx.ignoreLotReply)
    ctx.publish(FoupStateChanged(ctx.foupId, "LOADING", activeCount(state), 0, "STOCKER", lotId = ctx.scenario.scenarioId))
    ctx.publish(FoupArrivedAtPort(ctx.foupId, ctx.scenario.stocker.equipmentId, "STOCKER-PORT-1"))
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
    ctx.publish(FoupInTransit(ctx.foupId, from, to, scaledMs / 2))
    ctx.publish(FoupStateChanged(ctx.foupId, "IN_TRANSIT", activeCount(state), 0, "AMHS", lotId = ctx.scenario.scenarioId))
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
    ctx.publish(FoupArrivedAtPort(ctx.foupId, equipId, portId))
    val areaType = if (equipId.contains("LITHO")) "LITHO" else if (equipId.contains("CDSEM")) "METROLOGY" else equipId
    ctx.publish(FoupStateChanged(ctx.foupId, "ON_PORT", activeCount(state), 0, equipId, lotId = ctx.scenario.scenarioId))
    // 同步等待设备区 Actor 接受迁移（IDLE→LOADED）；设备状态（Load）由 Actor 接受后自行发布
    askArea(ctx, areaType, replyTo => EquipmentAreaActor.TrackIn(equipId, s"lot-${s.ledgerSeq}", replyTo)).map { _ =>
      s.copy(ledgerSeq = s.ledgerSeq + 1, currentArea = equipId)
    }(ctx.ec)
  }

  // ====================================================================
  // Stage: At Equipment (arrival)
  // ====================================================================
  def atEquipment(state: FabDemoState, ctx: FabDemoContext, area: String, equipId: String): Future[FabDemoState] = {
    val s = emitLedger(state, s"PhaseAt$area: FOUP at $area", ctx)
    ctx.stageProgress("AT_EQP", s"FOUP at $area", s"PhaseAt$area")
    ctx.lotRef ! RecordTransportCompleted(ctx.foupId, equipId, ctx.ignoreLotReply)
    ctx.publish(FoupArrivedAtPort(ctx.foupId, equipId, s"$equipId-PORT-1"))
    ctx.publish(FoupStateChanged(ctx.foupId, "AT_EQUIPMENT", activeCount(state), 0, area, lotId = ctx.scenario.scenarioId))
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
    // 等区域领取任务（LOADED→BUSY）后再宣告开始加工
    askArea(ctx, areaType, replyTo => EquipmentAreaActor.StartProcess(equipId, recipeId, scaledMs, replyTo)).flatMap { _ =>
      ctx.publish(ProcessingStarted(equipId, recipeId, scaledMs))
      ctx.adapter.sendCommand(equipId, ProcessRecipe(recipeId)).flatMap {
        case JobCompleted(jobId, _, _) =>
          ctx.lotRef ! RecordEquipmentJobCompleted(equipId, jobId, success = true, ctx.ignoreLotReply)
          ctx.publish(ProcessingCompleted(equipId, jobId, success = true, ""))
          askArea(ctx, areaType, replyTo => EquipmentAreaActor.FinishProcess(equipId, replyTo)).map { _ =>
            s.copy(ledgerSeq = s.ledgerSeq + 1)
          }(ctx.ec)
        case JobFailed(jobId, _, errorCode, detail) =>
          val err = StageError("Process", Some(equipId), errorCode, detail)
          ctx.publish(net.imadz.domain.events.PipelineStageFailed(err.stageName, err.equipId, err.errorCode, err.detail))
          ctx.publish(ProcessingCompleted(equipId, jobId, success = false, detail))
          // 设备级故障：区域 Actor 进入 DOWN（自行发布），5 秒自愈
          ctx.areaActorOf(areaType).foreach(_ ! EquipmentAreaActor.ReportFault(equipId, errorCode, detail))
          Future.failed(StageFailedException(err))
        case _ => Future.successful(s.copy(ledgerSeq = s.ledgerSeq + 1))
      }(ctx.ec)
    }(ctx.ec)
  }

  // ====================================================================
  // Stage: TrackOut (出站) — FOUP unloaded from equipment after processing
  // ====================================================================
  def trackOut(state: FabDemoState, ctx: FabDemoContext, equipId: String, portId: String = "LP1"): Future[FabDemoState] = {
    val s = emitLedger(state, s"PhaseTrackOut: $equipId port $portId", ctx)
    ctx.stageProgress("TRACK_OUT", s"$equipId:$portId unloading", "PhaseTrackOut")
    val areaType = if (equipId.contains("LITHO")) "LITHO" else if (equipId.contains("CDSEM")) "METROLOGY" else equipId
    // 物理顺序：先出站（区域 Actor: UNLOADING→IDLE），卸料完成后 FOUP 离开设备
    askArea(ctx, areaType, replyTo => EquipmentAreaActor.TrackOut(equipId, replyTo)).map { _ =>
      ctx.publish(FoupStateChanged(ctx.foupId, "UNLOADED", activeCount(state), 0, equipId, lotId = ctx.scenario.scenarioId))
      s.copy(ledgerSeq = s.ledgerSeq + 1)
    }(ctx.ec)
  }

  // ====================================================================
  // Stage: CD-SEM Measure
  // ====================================================================
  def measure(state: FabDemoState, ctx: FabDemoContext, equipId: String): Future[FabDemoState] = {
    val s = emitLedger(state, "PhaseMeasure: CD-SEM", ctx)
    ctx.stageProgress("MEASURING", "CD measurement", "PhaseMeasure")
    val scaledMs = (ctx.scenario.cdSem.processingTime.toMillis / ctx.speedMultiplier).toLong
    ctx.lotRef ! RecordEquipmentJobStarted(equipId, "CD-MEASURE-001", ctx.ignoreLotReply)
    askArea(ctx, "MET", replyTo => EquipmentAreaActor.StartProcess(equipId, "CD-MEASURE-001", scaledMs, replyTo)).flatMap { _ =>
      ctx.publish(ProcessingStarted(equipId, "CD-MEASURE-001", scaledMs))
      ctx.adapter.sendCommand(equipId, ProcessRecipe("CD-MEASURE-001")).flatMap {
        case JobCompleted(jobId, _, MetrologyResult(_, waferMeasurements)) =>
          ctx.lotRef ! RecordEquipmentJobCompleted(equipId, jobId, success = true, ctx.ignoreLotReply)
          ctx.publish(ProcessingCompleted(equipId, jobId, success = true, ""))
          askArea(ctx, "MET", replyTo => EquipmentAreaActor.FinishProcess(equipId, replyTo)).map { _ =>
            val cdValues: Map[String, Double] = waferMeasurements.map { case (wid, cd) => wid -> cd.measuredNm }
            logger.info(s"[Measure] CDSEM returned ${cdValues.size} wafer CD values: ${cdValues.map { case (k, v) => s"$k=$v" }.mkString(", ")}")
            val newWafers = s.wafers.map { case (wid, info) =>
              val cdVal = cdValues.get(wid)
              if (cdVal.isEmpty) logger.warn(s"[Measure] No CD value for wafer $wid in CDSEM response! Available: ${cdValues.keys.mkString(", ")}")
              wid -> info.copy(cdValueHistory = info.cdValueHistory ++ cdVal.toList)
            }
            s.copy(ledgerSeq = s.ledgerSeq + 1, wafers = newWafers)
          }(ctx.ec)
        case JobFailed(jobId, _, errorCode, detail) =>
          val err = StageError("Measure", Some(equipId), errorCode, detail)
          ctx.publish(net.imadz.domain.events.PipelineStageFailed(err.stageName, err.equipId, err.errorCode, err.detail))
          ctx.publish(ProcessingCompleted(equipId, jobId, success = false, detail))
          ctx.areaActorOf("MET").foreach(_ ! EquipmentAreaActor.ReportFault(equipId, errorCode, detail))
          Future.failed(StageFailedException(err))
        case other =>
          logger.warn(s"[Measure] Unexpected CDSEM response (expected MetrologyResult): ${other.getClass.getSimpleName}")
          Future.successful(s.copy(ledgerSeq = s.ledgerSeq + 1))
      }(ctx.ec)
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
    ctx.publish(FoupStateChanged(ctx.foupId, "COMPLETED", 0, 0, "STOCKER", lotId = ctx.scenario.scenarioId))
    ctx.publish(FoupArrivedAtPort(ctx.foupId, ctx.scenario.stocker.equipmentId, "STOCKER-PORT-1"))
    ctx.publish(LotUpdated(ctx.scenario.scenarioId, ctx.scenario.lotSize, state.scrapCount,
      (1 to state.iteration + 1).map(i => s"Completed-$i").toList, state.passCount, totalRework))
    ctx.publish(RecoveryCompleted(ctx.scenario.scenarioId, ctx.scenario.lotSize, state.passCount, totalRework, state.scrapCount))
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
    ctx.publish(LedgerStepAdvanced(state.ledgerSeq, name, nodeId, subProcess, branchDecision))
    state
  }

}
