package net.imadz.fab.chain

import net.imadz.application.aggregates.LotProtocol.{LotCommand, LotConfirmation, SealLot}
import net.imadz.application.aggregates.WaferProtocol.{HoldWafer, ReleaseHold, ScrapWafer, SkipWafer, WaferCommand, WaferConfirmation}
import net.imadz.application.aggregates.process.FabProcessProtocol.FabProcessCommand
import net.imadz.application.aggregates.process.FabProcessProtocol._
import net.imadz.application.services.transactor.FabSagaProtocol.FabSagaConfirmation
import net.imadz.common.CommonTypes.Id
import net.imadz.fab.chain.FabDemoPipeline.{FabDemoContext, FabDemoState}
import net.imadz.fab.events._
import net.imadz.fab.protocol._
import net.imadz.fab.scenario.{DecisionConfig, FabSimulationScenario}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

/**
 * Multi-scenario Fab pipeline for Send-Ahead, Scrap, Sampling, and Hold/Release scenarios.
 *
 * Uses [[FabDemoState]] and [[FabDemoContext]] from [[FabDemoPipeline]] for compatibility
 * with [[FabChainExecutor]] (shared recovery infrastructure).
 */
object FabScenarioPipeline {

  // ====================================================================
  // Pipeline runner
  // ====================================================================

  def runPipeline(initialState: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    val stages = ctx.scenario.scenarioId match {
      case "send-ahead-pilot" => sendAheadStages
      case "scrap-downgrade"  => scrapStages
      case "sampling-demo"    => samplingStages
      case "hold-release"     => holdReleaseStages
      case _                  => basicStages
    }
    runSequence(stages, initialState, ctx)
  }

  private def runSequence(stages: Seq[PipelineStage], init: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    stages.foldLeft(Future.successful(init)) { (f, stage) =>
      f.flatMap(state => runStage(stage, state, ctx))(ctx.ec)
    }
  }

  // ====================================================================
  // Stage ADT + interpreter
  // ====================================================================

  sealed trait PipelineStage
  case object LoadFoup extends PipelineStage
  case class Transport(from: String, to: String) extends PipelineStage
  case class AtEquipment(area: String, equipId: String) extends PipelineStage
  case class RunRecipe(equipId: String, recipeId: String) extends PipelineStage
  case class Measure(equipId: String) extends PipelineStage
  case object Classify extends PipelineStage
  case class SagaSplit(lotKey: String) extends PipelineStage
  case class SagaMerge(lotKey: String) extends PipelineStage
  case object ScrapWafers extends PipelineStage
  case object HoldWafers extends PipelineStage
  case object ReleaseWafers extends PipelineStage
  case class WaitForReview(durationMs: Long) extends PipelineStage
  case object SealComplete extends PipelineStage
  case class Branch(cond: FabDemoState => Boolean, ifTrue: Seq[PipelineStage], ifFalse: Seq[PipelineStage]) extends PipelineStage

  private def runStage(stage: PipelineStage, state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    implicit val ec: ExecutionContext = ctx.ec
    stage match {
      case LoadFoup                => loadFoup(state, ctx)
      case Transport(from, to)     => transport(state, ctx, from, to)
      case AtEquipment(area, eid)  => atEquipment(state, ctx, area, eid)
      case RunRecipe(eid, rid) => process(state, ctx, eid, rid)
      case Measure(eid)            => measure(state, ctx, eid)
      case Classify                => classifyStage(state, ctx)
      case SagaSplit(lotKey)       => sagaSplit(state, ctx, lotKey)
      case SagaMerge(lotKey)       => sagaMerge(state, ctx, lotKey)
      case ScrapWafers             => scrapWafers(state, ctx)
      case HoldWafers              => holdWafers(state, ctx)
      case ReleaseWafers           => releaseWafers(state, ctx)
      case WaitForReview(ms)       => waitForReview(state, ctx, ms)
      case SealComplete            => sealComplete(state, ctx)
      case Branch(cond, t, f)      => if (cond(state)) runSequence(t, state, ctx) else runSequence(f, state, ctx)
    }
  }

  // ====================================================================
  // Scenario recipes
  // ====================================================================

  private def basicStages: Seq[PipelineStage] = Seq(
    LoadFoup, Transport("STOCKER", "LITHO"), AtEquipment("LITHO", "LITHO-01"),
    RunRecipe("LITHO-01", "LITHO-28-001"),
    Transport("LITHO", "CDSEM"), AtEquipment("METROLOGY", "CDSEM-01"),
    Measure("CDSEM-01"), Classify,
    Transport("CDSEM", "STOCKER"), SealComplete
  )

  private def sendAheadStages: Seq[PipelineStage] = {
    val equipId = "LITHO-01"; val cdSemId = "CDSEM-01"
    Seq(
      LoadFoup, SagaSplit("pilot"),
      Transport("STOCKER", "LITHO"), AtEquipment("LITHO", equipId),
      RunRecipe(equipId, "PILOT-RECIPE-001"),
      Transport("LITHO", "CDSEM"), AtEquipment("METROLOGY", cdSemId),
      Measure(cdSemId), Classify,
      Branch(_.pilotPassed,
        Seq(SagaMerge("pilot"),
          Transport("STOCKER", "LITHO"), AtEquipment("LITHO", equipId),
          RunRecipe(equipId, "LITHO-28-001"),
          Transport("LITHO", "CDSEM"), AtEquipment("METROLOGY", cdSemId),
          Measure(cdSemId), Classify,
          Transport("CDSEM", "STOCKER"), SealComplete),
        Seq(ScrapWafers, SealComplete))
    )
  }

  private def scrapStages: Seq[PipelineStage] = {
    val equipId = "LITHO-01"; val cdSemId = "CDSEM-01"
    Seq(LoadFoup, Transport("STOCKER", "LITHO"), AtEquipment("LITHO", equipId),
      RunRecipe(equipId, "LITHO-28-001"),
      Transport("LITHO", "CDSEM"), AtEquipment("METROLOGY", cdSemId),
      Measure(cdSemId), Classify, ScrapWafers,
      Transport("CDSEM", "STOCKER"), SealComplete)
  }

  private def samplingStages: Seq[PipelineStage] = {
    val cdSemId = "CDSEM-01"
    Seq(LoadFoup, SagaSplit("sample"),
      Transport("STOCKER", "CDSEM"), AtEquipment("METROLOGY", cdSemId),
      Measure(cdSemId), Classify, SagaMerge("sample"),
      Transport("CDSEM", "STOCKER"), SealComplete)
  }

  private def holdReleaseStages: Seq[PipelineStage] = {
    val equipId = "LITHO-01"; val cdSemId = "CDSEM-01"
    Seq(LoadFoup, Transport("STOCKER", "LITHO"), AtEquipment("LITHO", equipId),
      RunRecipe(equipId, "LITHO-28-001"),
      Transport("LITHO", "CDSEM"), AtEquipment("METROLOGY", cdSemId),
      Measure(cdSemId), Classify,
      SagaSplit("hold"), HoldWafers, WaitForReview(15000), ReleaseWafers,
      Branch(_.reviewApproved,
        Seq(SagaMerge("hold"), Transport("CDSEM", "STOCKER"), SealComplete),
        Seq(ScrapWafers, Transport("CDSEM", "STOCKER"), SealComplete)))
  }

  // ====================================================================
  // Stage functions
  // ====================================================================

  private def loadFoup(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    emitLedger(state, "PhaseLoad: Load FOUP from Stocker", ctx)
    ctx.publisher(GlobalStatusChanged("LOADING", "Loading FOUP", "PhaseLoad"))
    ctx.processRef ! RecordFoupLoaded(ctx.foupId, ctx.scenario.stocker.equipmentId, ctx.ignoreReply)
    ctx.publisher(FoupStateChanged(ctx.foupId, "LOADING", activeCount(state), 0, "STOCKER", lotId = ctx.scenario.scenarioId))
    ctx.publisher(FoupArrivedAtPort(ctx.foupId, ctx.scenario.stocker.equipmentId, "STOCKER-PORT-1"))
    Future.successful(state.copy(ledgerSeq = state.ledgerSeq + 1))
  }

  private def transport(state: FabDemoState, ctx: FabDemoContext, from: String, to: String): Future[FabDemoState] = {
    val s = emitLedger(state, s"PhaseTransport: $from → $to", ctx)
    ctx.publisher(GlobalStatusChanged("TRANSPORTING", s"$from → $to", "PhaseTransport"))
    val routeMs = ctx.scenario.amhs.routes.get((from, to)).map(_.toMillis).getOrElse(2000L)
    val scaledMs = (routeMs / ctx.speedMultiplier).toLong
    ctx.processRef ! RecordTransportStarted(ctx.foupId, from, to, scaledMs, ctx.ignoreReply)
    ctx.publisher(FoupInTransit(ctx.foupId, from, to, scaledMs / 2))
    ctx.publisher(FoupStateChanged(ctx.foupId, "IN_TRANSIT", activeCount(state), 0, "AMHS", lotId = ctx.scenario.scenarioId))
    ctx.adapter.sendCommand("AMHS", TransferFoup(ctx.foupId, from, to)).map(_ => s.copy(ledgerSeq = s.ledgerSeq + 1))(ctx.ec)
  }

  private def atEquipment(state: FabDemoState, ctx: FabDemoContext, area: String, equipId: String): Future[FabDemoState] = {
    val s = emitLedger(state, s"PhaseAt$area: FOUP at $area", ctx)
    ctx.publisher(GlobalStatusChanged("AT_EQP", s"FOUP at $area", s"PhaseAt$area"))
    ctx.processRef ! RecordTransportCompleted(ctx.foupId, equipId, ctx.ignoreReply)
    ctx.publisher(FoupArrivedAtPort(ctx.foupId, equipId, s"$equipId-PORT-1"))
    ctx.publisher(FoupStateChanged(ctx.foupId, "AT_EQUIPMENT", activeCount(state), 0, area, lotId = ctx.scenario.scenarioId))
    val areaType = if (area == "LITHO") "LITHO" else "METROLOGY"
    ctx.publisher(EquipmentStateChanged(equipId, areaType, "Idle", None))
    Future.successful(s.copy(ledgerSeq = s.ledgerSeq + 1))
  }

  private def process(state: FabDemoState, ctx: FabDemoContext, equipId: String, recipeId: String): Future[FabDemoState] = {
    val s = emitLedger(state, s"PhaseProcess: $recipeId on $equipId", ctx)
    ctx.publisher(GlobalStatusChanged("PROCESSING", s"$equipId processing", "PhaseProcess"))
    val scaledMs = (ctx.scenario.litho.processingTime.toMillis / ctx.speedMultiplier).toLong
    ctx.processRef ! RecordEquipmentJobStarted(equipId, recipeId, ctx.ignoreReply)
    val areaType = if (equipId.contains("LITHO")) "LITHO" else "METROLOGY"
    ctx.publisher(EquipmentStateChanged(equipId, areaType, "Busy", Some(s"job-$recipeId")))
    ctx.publisher(ProcessingStarted(equipId, recipeId, scaledMs))
    ctx.adapter.sendCommand(equipId, ProcessRecipe(recipeId)).map {
      case JobCompleted(jobId, _, _) =>
        ctx.processRef ! RecordEquipmentJobCompleted(equipId, jobId, success = true, ctx.ignoreReply)
        ctx.publisher(ProcessingCompleted(equipId, jobId, success = true, ""))
        ctx.publisher(EquipmentStateChanged(equipId, areaType, "Idle", None))
        s.copy(ledgerSeq = s.ledgerSeq + 1)
      case _ => s.copy(ledgerSeq = s.ledgerSeq + 1)
    }(ctx.ec)
  }

  private def measure(state: FabDemoState, ctx: FabDemoContext, equipId: String): Future[FabDemoState] = {
    val s = emitLedger(state, "PhaseMeasure: CD-SEM", ctx)
    ctx.publisher(GlobalStatusChanged("MEASURING", "CD measurement", "PhaseMeasure"))
    val scaledMs = (ctx.scenario.cdSem.processingTime.toMillis / ctx.speedMultiplier).toLong
    ctx.processRef ! RecordEquipmentJobStarted(equipId, "CD-MEASURE-001", ctx.ignoreReply)
    ctx.publisher(EquipmentStateChanged(equipId, "METROLOGY", "Busy", Some("metrology-job")))
    ctx.publisher(ProcessingStarted(equipId, "CD-MEASURE-001", scaledMs))
    ctx.adapter.sendCommand(equipId, ProcessRecipe("CD-MEASURE-001")).map {
      case JobCompleted(jobId, _, MetrologyResult(_, waferMeasurements)) =>
        ctx.processRef ! RecordEquipmentJobCompleted(equipId, jobId, success = true, ctx.ignoreReply)
        ctx.publisher(ProcessingCompleted(equipId, jobId, success = true, ""))
        ctx.publisher(EquipmentStateChanged(equipId, "METROLOGY", "Idle", None))
        val cdValues: Map[String, Double] = waferMeasurements.map { case (wid, cd) => wid -> cd.measuredNm }
        s.copy(ledgerSeq = s.ledgerSeq + 1, wafers = s.wafers.map { case (wid, info) =>
          wid -> info.copy(cdValueHistory = info.cdValueHistory ++ cdValues.get(wid).toList)
        })
      case _ => s.copy(ledgerSeq = s.ledgerSeq + 1)
    }(ctx.ec)
  }

  private def classifyStage(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    val s = emitLedger(state, "PhaseClassify: Decision Engine", ctx)
    ctx.publisher(GlobalStatusChanged("CLASSIFYING", "Decision", "PhaseClassify"))
    val scenId = ctx.scenario.scenarioId
    var updatedWafers = state.wafers
    var scrapWafers = Seq.empty[String]
    var pilotPassed = state.pilotPassed
    var spawnedChild = state.spawnedChildLotKey

    state.wafers.filter { case (_, w) => w.classification.isEmpty }.foreach { case (wid, info) =>
      val cdValue = info.cdValueHistory.lastOption.getOrElse(32.0)
      val dCfg = ctx.scenario.decision
      val cls = if (cdValue >= dCfg.lowerSpecNm && cdValue <= dCfg.upperSpecNm) "PASS"
                else if (cdValue > dCfg.upperSpecNm && cdValue <= dCfg.upperSpecNm + dCfg.borderlineWindowNm) "BORDERLINE"
                else if (cdValue > dCfg.upperSpecNm + 8.0) "SCRAP"
                else "FAIL"
      ctx.processRef ! RecordWaferMeasured(wid, cdValue, ctx.ignoreReply)
      ctx.publisher(MeasurementResultEvent(wid, cdValue, cls, dCfg.upperSpecNm))

      scenId match {
        case "send-ahead-pilot" =>
          if (cls == "PASS" || cls == "BORDERLINE") {
            updatedWafers += wid -> info.copy(classification = Some("PASS"))
            pilotPassed = true
            ctx.publisher(DecisionMade(wid, "Pilot PASS → Merge back", None))
          } else {
            updatedWafers += wid -> info.copy(classification = Some("SCRAP"))
            scrapWafers :+= wid; pilotPassed = false
            ctx.publisher(DecisionMade(wid, "Pilot FAIL → Scrap", None))
          }
        case "scrap-downgrade" =>
          updatedWafers += wid -> info.copy(classification = Some(cls))
          if (cls == "SCRAP") { scrapWafers :+= wid; ctx.publisher(DecisionMade(wid, "SCRAP → Terminate", None)) }
          else ctx.publisher(DecisionMade(wid, s"$cls → Continue", None))
        case "sampling-demo" =>
          updatedWafers += wid -> info.copy(classification = Some(cls))
          if (cls == "SCRAP") scrapWafers :+= wid
          ctx.publisher(DecisionMade(wid, s"$cls → Continue", None))
        case "hold-release" =>
          if (cls == "BORDERLINE" && spawnedChild.isEmpty) {
            updatedWafers += wid -> info.copy(classification = Some("HOLD"), subLot = Some("hold"))
            spawnedChild = Some("hold")
            ctx.publisher(DecisionMade(wid, "BORDERLINE → Hold for Review", None))
          } else {
            updatedWafers += wid -> info.copy(classification = Some(if (cls == "HOLD") "PASS" else cls))
            if (cls == "SCRAP") { scrapWafers :+= wid; ctx.publisher(DecisionMade(wid, "SCRAP → Terminate", None)) }
            else ctx.publisher(DecisionMade(wid, s"$cls → Continue", None))
          }
        case _ =>
          updatedWafers += wid -> info.copy(classification = Some(cls))
          if (cls == "SCRAP") scrapWafers :+= wid
      }
      ctx.processRef ! RecordWaferClassified(wid, cls, 0, cdValue, ctx.ignoreReply)
    }

    scrapWafers.foreach { wid =>
      ctx.waferRefs.get(wid).foreach { ref =>
        ref ! ScrapWafer("Classified as SCRAP", ctx.ignoreWaferReply)
      }
    }

    val totalPass = updatedWafers.values.count(w => !w.classification.contains("SCRAP") && !w.classification.contains("HOLD"))
    val totalScrap = updatedWafers.values.count(_.classification.contains("SCRAP"))
    ctx.publisher(LotUpdated(ctx.scenario.scenarioId, ctx.scenario.lotSize, totalScrap, List("Completed"), totalPass, 0))
    ctx.publisher(buildAggregateState(updatedWafers, ctx, totalPass, totalScrap))

    Future.successful(s.copy(wafers = updatedWafers, passCount = totalPass, scrapCount = totalScrap,
      ledgerSeq = s.ledgerSeq + 1, pilotPassed = pilotPassed, spawnedChildLotKey = spawnedChild))
  }

  private def sagaSplit(state: FabDemoState, ctx: FabDemoContext, lotKey: String): Future[FabDemoState] = {
    val s = emitLedger(state, s"PhaseSplit: Saga Split → $lotKey", ctx)
    ctx.publisher(GlobalStatusChanged("SPLITTING", s"Saga TCC split → $lotKey", "PhaseSplit"))
    val childLotId = ctx.childLotIds.getOrElse(lotKey, ctx.reworkLotId)
    val wafersToMove: Set[Id] = state.wafers.filter { case (_, info) =>
      info.subLot.contains(lotKey) || lotKey == state.spawnedChildLotKey.getOrElse("")
    }.flatMap { case (wid, _) => ctx.waferUUIDs.get(wid) }.toSet
    // Fallback: if no wafers have subLot yet, use first N
    val moveIds = if (wafersToMove.nonEmpty) wafersToMove else {
      lotKey match {
        case "pilot"  => ctx.scenario.waferIds.take(1).flatMap(ctx.waferUUIDs.get).toSet
        case "sample" => ctx.scenario.waferIds.take(2).flatMap(ctx.waferUUIDs.get).toSet
        case _ => Set.empty[Id]
      }
    }
    val sagaId = s"SAGA-SPLIT-$lotKey-${state.iteration}"
    val rwkLotName = s"${ctx.scenario.scenarioId}-${lotKey.toUpperCase}"
    ctx.publisher(SagaOperationEvent(sagaId, "SplitLot", "PREPARE", ctx.scenario.scenarioId, rwkLotName, moveIds.toSeq.map(_.toString)))
    ctx.publisher(FoupStateChanged(ctx.foupId, "SPLITTING", activeCount(state), moveIds.size, "CDSEM",
      lotId = ctx.scenario.scenarioId, reworkLotId = rwkLotName))
    ctx.sagaTx(ctx.sourceLotId, childLotId, moveIds).map { confirmation =>
      if (confirmation.error.isEmpty) {
        ctx.publisher(SagaOperationEvent(sagaId, "SplitLot", "COMMITTED", ctx.scenario.scenarioId, rwkLotName, moveIds.toSeq.map(_.toString)))
        val updatedWafers = state.wafers.map { case (wid, info) =>
          if (moveIds.contains(ctx.waferUUIDs.getOrElse(wid, UUID.nameUUIDFromBytes("none".getBytes))))
            wid -> info.copy(subLot = Some(lotKey))
          else wid -> info
        }
        s.copy(wafers = updatedWafers, ledgerSeq = s.ledgerSeq + 1)
      } else {
        ctx.publisher(SagaOperationEvent(sagaId, "SplitLot", "FAILED", "", "", Seq.empty))
        s.copy(ledgerSeq = s.ledgerSeq + 1)
      }
    }(ctx.ec)
  }

  private def sagaMerge(state: FabDemoState, ctx: FabDemoContext, lotKey: String): Future[FabDemoState] = {
    val s = emitLedger(state, s"PhaseMerge: Saga Merge ← $lotKey", ctx)
    ctx.publisher(GlobalStatusChanged("MERGING", s"Saga Merge ← $lotKey", "PhaseMerge"))
    val childLotId = ctx.childLotIds.getOrElse(lotKey, ctx.reworkLotId)
    val wafersToMove: Set[Id] = state.wafers.filter { case (_, info) => info.subLot.contains(lotKey) }
      .flatMap { case (wid, _) => ctx.waferUUIDs.get(wid) }.toSet
    // Fallback for send-ahead: if no subLot tracking, move pilot wafers
    val moveIds = if (wafersToMove.nonEmpty) wafersToMove else {
      lotKey match {
        case "pilot"  => ctx.scenario.waferIds.take(1).flatMap(ctx.waferUUIDs.get).toSet
        case "sample" => ctx.scenario.waferIds.take(2).flatMap(ctx.waferUUIDs.get).toSet
        case "hold"   => state.wafers.filter(_._2.subLot.contains("hold")).flatMap { case (wid, _) => ctx.waferUUIDs.get(wid) }.toSet
        case _ => Set.empty[Id]
      }
    }
    val sagaId = s"SAGA-MERGE-$lotKey-${state.iteration}"
    val rwkLotName = s"${ctx.scenario.scenarioId}-${lotKey.toUpperCase}"
    ctx.publisher(SagaOperationEvent(sagaId, "MergeLot", "PREPARE", rwkLotName, ctx.scenario.scenarioId, moveIds.toSeq.map(_.toString)))
    ctx.sagaTx(childLotId, ctx.sourceLotId, moveIds).map { confirmation =>
      if (confirmation.error.isEmpty) {
        ctx.publisher(SagaOperationEvent(sagaId, "MergeLot", "COMMITTED", rwkLotName, ctx.scenario.scenarioId, moveIds.toSeq.map(_.toString)))
        val mergedWafers = state.wafers.map { case (wid, info) =>
          if (moveIds.contains(ctx.waferUUIDs.getOrElse(wid, java.util.UUID.nameUUIDFromBytes("none".getBytes))))
            wid -> info.copy(subLot = None, classification = Some("PASS"))
          else wid -> info
        }
        s.copy(wafers = mergedWafers, ledgerSeq = s.ledgerSeq + 1)
      } else {
        val errMsg = confirmation.error.getOrElse("unknown")
        ctx.publisher(SagaOperationEvent(sagaId, "MergeLot", s"FAILED: $errMsg", rwkLotName, ctx.scenario.scenarioId, moveIds.toSeq.map(_.toString)))
        ctx.publisher(OrchestratorCommand(cmdId(), "SAGA-TCC", "MergeFailed",
          s"Merge $lotKey failed: $errMsg — continuing pipeline", moveIds.toSeq.map(_.toString)))
        // Mark wafers as PASS anyway so pipeline completes cleanly
        val mergedWafers = state.wafers.map { case (wid, info) =>
          if (moveIds.contains(ctx.waferUUIDs.getOrElse(wid, java.util.UUID.nameUUIDFromBytes("none".getBytes))))
            wid -> info.copy(subLot = None, classification = Some("PASS"))
          else wid -> info
        }
        s.copy(wafers = mergedWafers, ledgerSeq = s.ledgerSeq + 1)
      }
    }(ctx.ec)
  }

  private def scrapWafers(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    val s = emitLedger(state, "PhaseScrap: Scrap classified wafers", ctx)
    state.wafers.filter(_._2.classification.contains("SCRAP")).keys.foreach { wid =>
      ctx.waferRefs.get(wid).foreach { ref => ref ! ScrapWafer("CD out of spec", ctx.ignoreWaferReply) }
      ctx.publisher(ScrapEvent(wid, "CD out of spec → SCRAP"))
    }
    Future.successful(s.copy(ledgerSeq = s.ledgerSeq + 1))
  }

  private def holdWafers(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    val s = emitLedger(state, "PhaseHold: Hold for engineering review", ctx)
    ctx.publisher(GlobalStatusChanged("HOLDING", "Engineering review", "PhaseHold"))
    state.wafers.filter(_._2.subLot.contains("hold")).keys.foreach { wid =>
      ctx.waferRefs.get(wid).foreach { ref => ref ! HoldWafer("Borderline CD — review required", ctx.ignoreWaferReply) }
    }
    val holdIds = state.wafers.filter(_._2.subLot.contains("hold")).keys.toSet
    ctx.processRef ! RecordWafersHeld(holdIds, "Borderline CD", ctx.ignoreReply)
    ctx.publisher(FoupStateChanged(ctx.foupId, "HELD", activeCount(state), holdIds.size, "CDSEM",
      lotId = ctx.scenario.scenarioId, reworkLotId = s"${ctx.scenario.scenarioId}-HLD"))
    Future.successful(s.copy(ledgerSeq = s.ledgerSeq + 1))
  }

  private def releaseWafers(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    val s = emitLedger(state, "PhaseRelease: Release held wafers", ctx)
    ctx.publisher(GlobalStatusChanged("RELEASING", "Review passed, releasing", "PhaseRelease"))
    state.wafers.filter(_._2.subLot.contains("hold")).keys.foreach { wid =>
      ctx.waferRefs.get(wid).foreach { ref => ref ! ReleaseHold(ctx.ignoreWaferReply) }
    }
    val holdIds = state.wafers.filter(_._2.subLot.contains("hold")).keys.toSet
    ctx.processRef ! RecordWafersReleased(holdIds, ctx.ignoreReply)
    Future.successful(s.copy(ledgerSeq = s.ledgerSeq + 1, reviewApproved = true))
  }

  private def waitForReview(state: FabDemoState, ctx: FabDemoContext, durationMs: Long): Future[FabDemoState] = {
    val s = emitLedger(state, s"PhaseReview: Engineer review (${durationMs / 1000}s)", ctx)
    ctx.publisher(OrchestratorCommand(cmdId(), "ENGINEER-REVIEW", "Review",
      s"Reviewing held wafers (${durationMs / 1000}s)", state.wafers.filter(_._2.subLot.contains("hold")).keys.toSeq))
    implicit val ec: ExecutionContext = ctx.ec
    Future { Thread.sleep(durationMs); s.copy(ledgerSeq = s.ledgerSeq + 1) }(ctx.ec)
  }

  private def sealComplete(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    val s = emitLedger(state, "PhaseComplete: Lot sealed", ctx)
    ctx.publisher(GlobalStatusChanged("COMPLETED", "Demo completed", "PhaseComplete"))
    ctx.lotRef ! SealLot(ctx.ignoreLotReply)
    ctx.processRef ! CompleteProcess(ctx.scenario.scenarioId, state.passCount, state.scrapCount, 0, ctx.ignoreReply)
    ctx.publisher(FoupStateChanged(ctx.foupId, "COMPLETED", 0, 0, "STOCKER", lotId = ctx.scenario.scenarioId))
    ctx.publisher(FoupArrivedAtPort(ctx.foupId, ctx.scenario.stocker.equipmentId, "STOCKER-PORT-1"))
    ctx.publisher(LotUpdated(ctx.scenario.scenarioId, ctx.scenario.lotSize, state.scrapCount, List("Completed"), state.passCount, 0))
    ctx.publisher(buildAggregateState(state.wafers, ctx, state.passCount, state.scrapCount))
    ctx.publisher(DemoCompleted(ctx.scenario.scenarioId, ctx.scenario.lotSize, state.passCount, 0, state.scrapCount))
    Future.successful(s.copy(ledgerSeq = s.ledgerSeq + 1))
  }

  // ====================================================================
  // Helpers
  // ====================================================================

  private def activeCount(state: FabDemoState): Int =
    state.wafers.values.count(w => w.classification.isEmpty || !w.classification.contains("SCRAP"))

  private def cmdId(): String = UUID.randomUUID().toString.take(8)

  private def emitLedger(state: FabDemoState, name: String, ctx: FabDemoContext): FabDemoState = {
    ctx.publisher(LedgerStepAdvanced(state.ledgerSeq, name))
    state
  }

  private def buildAggregateState(wafers: Map[String, FabDemoPipeline.WaferInfo], ctx: FabDemoContext, totalPass: Int, totalScrap: Int): AggregateStateUpdated = {
    val srcLotId = ctx.scenario.scenarioId
    val sourceLot = LotStateSnapshot(srcLotId, "Active", ctx.scenario.lotSize, totalPass, totalScrap)
    val childLots = stateToChildLots(wafers, ctx)
    val waferSnapshots = wafers.map { case (wid, info) =>
      val waferLot = info.subLot.map(k => s"$srcLotId-${k.toUpperCase}").getOrElse(srcLotId)
      WaferStateSnapshot(wid,
        status = if (info.classification.contains("SCRAP")) "Scrapped"
                 else if (info.classification.contains("HOLD")) "OnHold"
                 else "Active",
        lotId = waferLot, classification = info.classification.getOrElse("Pending"), reworkCount = info.reworkCount)
    }.toSeq
    AggregateStateUpdated(sourceLot, childLots.headOption, waferSnapshots)
  }

  private def stateToChildLots(wafers: Map[String, FabDemoPipeline.WaferInfo], ctx: FabDemoContext): List[LotStateSnapshot] = {
    wafers.values.flatMap(_.subLot).toSet.toList.map { (key: String) =>
      val count = wafers.values.count(_.subLot.contains(key))
      LotStateSnapshot(s"${ctx.scenario.scenarioId}-${key.toUpperCase}", "Active", count, 0, 0)
    }
  }
}
