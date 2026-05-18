package net.imadz.fab.chain

import akka.util.Timeout
import net.imadz.application.aggregates.LotProtocol.{LotConfirmation, SealLot}
import net.imadz.application.aggregates.LotProtocol._
import net.imadz.common.CommonTypes.Id
import net.imadz.domain.entities.LotEntity.{HoldSplit, PilotSplit, ReworkSplit, SampleSplit, ScrapSplit, SplitReason}
import net.imadz.fab.model.FabExecutionModel.{FabDemoContext, FabDemoState, StageError, StageFailedException}
import net.imadz.fab.events._

import net.imadz.fab.routing.{OcapEngine, OcapRuleDefinition, ReworkLoop, SendAheadPilot, SubProcessRef,
  OcapActionPlan, OcapRework, OcapScrap, OcapHold, OcapNotify, OcapAdjustRecipe, OcapComposite}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

/**
 * Multi-scenario Fab pipeline for Send-Ahead, Scrap, Sampling, and Hold/Release scenarios.
 *
 * Stage functions delegate to [[PipelineStages]]; only scenario-specific orchestration
 * (classify logic, saga split/merge, hold/release) remains here.
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

  /** Run an arbitrary sequence of PipelineStages. Public entry point for route-based execution. */
  def runStages(stages: Seq[PipelineStage], init: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    runSequence(stages, init, ctx)
  }

  private def runSequence(stages: Seq[PipelineStage], init: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    stages.foldLeft(Future.successful(init)) { (f, stage) =>
      f.flatMap(state =>
        runStage(stage, state, ctx).recoverWith {
          case StageFailedException(err) =>
            ctx.publisher(PipelineStageFailed(err.stageName, err.equipId, err.errorCode, err.detail))
            ctx.publisher(GlobalStatusChanged("FAILED", s"${err.stageName}: ${err.detail}", "PhaseFailed"))
            invokeOcapInterceptor(state, ctx, err)
          case ex: Exception =>
            ctx.publisher(GlobalStatusChanged("ERROR", s"Unexpected: ${ex.getMessage}", "PhaseFailed"))
            Future.successful(state)
        }(ctx.ec)
      )(ctx.ec)
    }
  }

  // ====================================================================
  // Stage ADT + interpreter
  // ====================================================================

  sealed trait PipelineStage
  case object LoadFoup extends PipelineStage
  case class Transport(from: String, to: String) extends PipelineStage
  case class AtEquipment(area: String, equipId: String) extends PipelineStage
  case class TrackIn(equipId: String, portId: String = "LP1") extends PipelineStage
  case class RunRecipe(equipId: String, recipeId: String) extends PipelineStage
  case class TrackOut(equipId: String, portId: String = "LP1") extends PipelineStage
  case class Measure(equipId: String) extends PipelineStage
  case object Classify extends PipelineStage
  case class SagaSplit(lotKey: String) extends PipelineStage
  case class SagaMerge(lotKey: String) extends PipelineStage
  case object ScrapWafers extends PipelineStage
  case object HoldWafers extends PipelineStage
  case object ReleaseWafers extends PipelineStage
  case object PostReleaseClassify extends PipelineStage
  case class WaitForReview(durationMs: Long) extends PipelineStage
  case object SealComplete extends PipelineStage
  case class Branch(cond: FabDemoState => Boolean, ifTrue: Seq[PipelineStage], ifFalse: Seq[PipelineStage]) extends PipelineStage
  case object PilotSubFlow extends PipelineStage
  // Unified IR extensions (M3.5+)
  case class OcapEvaluate(rules: List[OcapRuleDefinition]) extends PipelineStage
  case class ExecuteSubProcess(ref: SubProcessRef) extends PipelineStage

  private[chain] def runStage(stage: PipelineStage, state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    implicit val ec: ExecutionContext = ctx.ec
    stage match {
      case LoadFoup             => PipelineStages.loadFoup(state, ctx)
      case Transport(from, to)  => PipelineStages.transport(state, ctx, from, to)
      case TrackIn(eid, port)    => PipelineStages.trackIn(state, ctx, eid, port)
      case AtEquipment(area, eid) => PipelineStages.atEquipment(state, ctx, area, eid)
      case RunRecipe(eid, rid)  => PipelineStages.process(state, ctx, eid, rid, areaTypeFor(eid))
      case TrackOut(eid, port)   => PipelineStages.trackOut(state, ctx, eid, port)
      case Measure(eid)         => PipelineStages.measure(state, ctx, eid)
      case Classify             => classifyStage(state, ctx)
      case SagaSplit(lotKey)    => sagaSplit(state, ctx, lotKey)
      case SagaMerge(lotKey)    => sagaMerge(state, ctx, lotKey)
      case ScrapWafers          => scrapWafers(state, ctx)
      case HoldWafers           => holdWafers(state, ctx)
      case ReleaseWafers        => releaseWafers(state, ctx)
      case PostReleaseClassify  => postReleaseClassify(state, ctx)
      case WaitForReview(ms)    => waitForReview(state, ctx, ms)
      case SealComplete         => PipelineStages.sealComplete(state, ctx)
      case Branch(cond, t, f)   => if (cond(state)) runSequence(t, state, ctx) else runSequence(f, state, ctx)
      case PilotSubFlow           => runPilotSubFlow(state, ctx)
      case OcapEvaluate(rules)    => ocapEvaluate(state, ctx, rules)
      case ExecuteSubProcess(ref) => executeSubProcess(state, ctx, ref, nodeId = Some(ref.nodeId))
    }
  }

  private def areaTypeFor(equipId: String): String =
    if (equipId.contains("LITHO")) "LITHO" else if (equipId.contains("CDSEM")) "METROLOGY" else equipId

  // ====================================================================
  // Scenario recipes
  // ====================================================================

  private[chain] def basicStages: Seq[PipelineStage] = Seq(
    LoadFoup,
    Transport("STOCKER", "LITHO"), AtEquipment("LITHO", "LITHO-01"),
    TrackIn("LITHO-01"), RunRecipe("LITHO-01", "LITHO-28-001"), TrackOut("LITHO-01"),
    Transport("LITHO", "CDSEM"), AtEquipment("METROLOGY", "CDSEM-01"),
    TrackIn("CDSEM-01"), Measure("CDSEM-01"), TrackOut("CDSEM-01"), Classify,
    Transport("CDSEM", "STOCKER"), SealComplete
  )

  private[chain] def sendAheadStages: Seq[PipelineStage] = {
    val equipId = "LITHO-01"; val cdSemId = "CDSEM-01"
    Seq(
      LoadFoup, SagaSplit("pilot"), PilotSubFlow,
      Branch(_.pilotPassed,
        Seq(SagaMerge("pilot"),
          Transport("STOCKER", "LITHO"), AtEquipment("LITHO", equipId),
          TrackIn(equipId), RunRecipe(equipId, "LITHO-28-001"), TrackOut(equipId),
          Transport("LITHO", "CDSEM"), AtEquipment("METROLOGY", cdSemId),
          TrackIn(cdSemId), Measure(cdSemId), TrackOut(cdSemId), Classify,
          Transport("CDSEM", "STOCKER"), SealComplete),
        Seq(ScrapWafers, SealComplete))
    )
  }

  private[chain] def scrapStages: Seq[PipelineStage] = {
    val equipId = "LITHO-01"; val cdSemId = "CDSEM-01"
    Seq(LoadFoup,
      Transport("STOCKER", "LITHO"), AtEquipment("LITHO", equipId),
      TrackIn(equipId), RunRecipe(equipId, "LITHO-28-001"), TrackOut(equipId),
      Transport("LITHO", "CDSEM"), AtEquipment("METROLOGY", cdSemId),
      TrackIn(cdSemId), Measure(cdSemId), TrackOut(cdSemId), Classify,
      SagaSplit("scrap"), ScrapWafers,
      Transport("CDSEM", "STOCKER"), SealComplete)
  }

  private[chain] def samplingStages: Seq[PipelineStage] = {
    val cdSemId = "CDSEM-01"
    Seq(LoadFoup, SagaSplit("sample"),
      Transport("STOCKER", "CDSEM"), AtEquipment("METROLOGY", cdSemId),
      TrackIn(cdSemId), Measure(cdSemId), TrackOut(cdSemId), Classify,
      SagaMerge("sample"),
      Transport("CDSEM", "STOCKER"), SealComplete)
  }

  private[chain] def holdReleaseStages: Seq[PipelineStage] = {
    val equipId = "LITHO-01"; val cdSemId = "CDSEM-01"
    Seq(LoadFoup,
      Transport("STOCKER", "LITHO"), AtEquipment("LITHO", equipId),
      TrackIn(equipId), RunRecipe(equipId, "LITHO-28-001"), TrackOut(equipId),
      Transport("LITHO", "CDSEM"), AtEquipment("METROLOGY", cdSemId),
      TrackIn(cdSemId), Measure(cdSemId), TrackOut(cdSemId), Classify,
      SagaSplit("hold"), HoldWafers, WaitForReview(15000), ReleaseWafers,
      Branch(_.reviewApproved,
        Seq(SagaMerge("hold"), PostReleaseClassify, Transport("CDSEM", "STOCKER"), SealComplete),
        Seq(ScrapWafers, Transport("CDSEM", "STOCKER"), SealComplete)))
  }

  // ====================================================================
  // Scenario-specific stages (not shared with FabDemoPipeline/FabFlowEngine)
  // ====================================================================

  private def classifyStage(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    val s = PipelineStages.emitLedger(state, "PhaseClassify: Decision Engine", ctx)
    ctx.publisher(GlobalStatusChanged("CLASSIFYING", "Decision", "PhaseClassify"))
    val scenId = ctx.scenario.scenarioId
    var updatedWafers = state.wafers
    var scrapWafers = Seq.empty[String]
    var spawnedChild = state.spawnedChildLotKey

    state.wafers.filter { case (_, w) => w.classification.isEmpty }.foreach { case (wid, info) =>
      val cdValue = info.cdValueHistory.lastOption.getOrElse(32.0)
      val cls = PipelineStages.classifyCd(cdValue, ctx.scenario.decision)
      ctx.lotRef ! RecordWaferMeasured(ctx.waferUUIDs(wid), cdValue, ctx.ignoreLotReply)
      ctx.publisher(MeasurementResultEvent(wid, cdValue, cls, ctx.scenario.decision.upperSpecNm))

      scenId match {
        case "scrap-downgrade" =>
          updatedWafers += wid -> info.copy(classification = Some(cls),
            subLot = if (cls == "SCRAP") Some("scrap") else None)
          if (cls == "SCRAP") { scrapWafers :+= wid; ctx.publisher(DecisionMade(wid, "SCRAP → Scrap Lot", None)) }
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
      ctx.lotRef ! RecordWaferClassified(ctx.waferUUIDs(wid),cls, 0, cdValue, ctx.ignoreLotReply)
    }

    val totalPass = updatedWafers.values.count(w => !w.classification.contains("SCRAP") && !w.classification.contains("HOLD"))
    val totalScrap = updatedWafers.values.count(_.classification.contains("SCRAP"))
    ctx.publisher(LotUpdated(ctx.scenario.scenarioId, ctx.scenario.lotSize, totalScrap, List("Completed"), totalPass, 0))

    Future.successful(s.copy(wafers = updatedWafers, passCount = totalPass, scrapCount = totalScrap,
      ledgerSeq = s.ledgerSeq + 1, spawnedChildLotKey = spawnedChild))
  }

  // ====================================================================
  // Send-Ahead pilot sub-flow — uses pilot child lot ref for equipment commands
  // ====================================================================
  private def runPilotSubFlow(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    implicit val ec: ExecutionContext = ctx.ec
    val pilotLotRef = ctx.childLotRefs.getOrElse("pilot", ctx.reworkLotRef)
    val pilotCtx = ctx.copy(lotRef = pilotLotRef)
    val equipId = "LITHO-01"; val cdSemId = "CDSEM-01"
    for {
      s1 <- PipelineStages.transport(state, pilotCtx, "STOCKER", "LITHO")
      s2 <- PipelineStages.atEquipment(s1, pilotCtx, "LITHO", equipId)
      s3 <- PipelineStages.process(s2, pilotCtx, equipId, "PILOT-RECIPE-001", "LITHO")
      s4 <- PipelineStages.transport(s3, pilotCtx, "LITHO", "CDSEM")
      s5 <- PipelineStages.atEquipment(s4, pilotCtx, "METROLOGY", cdSemId)
      s6 <- PipelineStages.measure(s5, pilotCtx, cdSemId)
      s7 <- classifyPilotWafer(s6, pilotCtx)
    } yield s7
  }

  /** Classify only the pilot wafer (subLot="pilot"), sends commands to pilot lot. */
  private def classifyPilotWafer(state: FabDemoState, pilotCtx: FabDemoContext): Future[FabDemoState] = {
    implicit val ec: ExecutionContext = pilotCtx.ec
    val s = PipelineStages.emitLedger(state, "PhaseClassify: Pilot wafer", pilotCtx)
    pilotCtx.publisher(GlobalStatusChanged("CLASSIFYING", "Classifying pilot wafer", "PhaseClassify"))
    var updatedWafers = state.wafers
    var pilotPassed = false

    state.wafers.filter { case (_, w) =>
      w.subLot.contains("pilot") && w.classification.isEmpty
    }.foreach { case (wid, info) =>
      val cdValue = info.cdValueHistory.lastOption.getOrElse(32.0)
      val cls = PipelineStages.classifyCd(cdValue, pilotCtx.scenario.decision)
      pilotCtx.lotRef ! RecordWaferMeasured(pilotCtx.waferUUIDs(wid), cdValue, pilotCtx.ignoreLotReply)
      pilotCtx.publisher(MeasurementResultEvent(wid, cdValue, cls, pilotCtx.scenario.decision.upperSpecNm))

      if (cls == "PASS" || cls == "BORDERLINE") {
        updatedWafers += wid -> info.copy(classification = Some("PASS"))
        pilotPassed = true
        pilotCtx.publisher(DecisionMade(wid, "Pilot PASS → Merge back", None))
      } else {
        updatedWafers += wid -> info.copy(classification = Some("SCRAP"), subLot = Some("scrap"))
        pilotPassed = false
        pilotCtx.publisher(DecisionMade(wid, "Pilot FAIL → Scrap", None))
      }
      pilotCtx.lotRef ! RecordWaferClassified(pilotCtx.waferUUIDs(wid), cls, 0, cdValue, pilotCtx.ignoreLotReply)
    }

    val totalPass = updatedWafers.values.count(w => !w.classification.contains("SCRAP") && !w.classification.contains("HOLD"))
    val totalScrap = updatedWafers.values.count(_.classification.contains("SCRAP"))
    pilotCtx.publisher(LotUpdated(pilotCtx.scenario.scenarioId, pilotCtx.scenario.lotSize, totalScrap, List("Pilot"), totalPass, 0))

    Future.successful(s.copy(wafers = updatedWafers, passCount = totalPass, scrapCount = totalScrap,
      ledgerSeq = s.ledgerSeq + 1, pilotPassed = pilotPassed))
  }

  private def sagaSplit(state: FabDemoState, ctx: FabDemoContext, lotKey: String): Future[FabDemoState] = {
    implicit val timeout: Timeout = 10.seconds
    val s = PipelineStages.emitLedger(state, s"PhaseSplit: Saga Split → $lotKey", ctx)
    ctx.publisher(GlobalStatusChanged("SPLITTING", s"Saga TCC split → $lotKey", "PhaseSplit"))
    val childLotId = ctx.childLotIds.getOrElse(lotKey, ctx.reworkLotId)
    val childLotRef = ctx.childLotRefs.getOrElse(lotKey, ctx.reworkLotRef)

    // Lazy-create child lot (idempotent — no-op if already exists)
    val splitReason: SplitReason = lotKey match {
      case "pilot"  => PilotSplit
      case "sample" => SampleSplit
      case "hold"   => HoldSplit
      case "scrap"  => ScrapSplit
      case _        => ReworkSplit
    }
    val childProductName = s"FAB-${lotKey.toUpperCase}-${ctx.sourceLotId.toString.take(8)}"
    val createChild: Future[LotConfirmation] =
      childLotRef.ask[LotConfirmation](ref => CreateLot(childProductName, Map.empty, ref,
        parentLotId = Some(ctx.sourceLotId), splitReason = Some(splitReason)))

    val waferEntries = state.wafers.filter { case (_, info) =>
      info.subLot.contains(lotKey)
    }
    val moveIds = waferEntries.flatMap { case (wid, _) => ctx.waferUUIDs.get(wid) }.toSet
    val moveNames = waferEntries.keys.toSet
    val finalMoveIds = if (moveIds.nonEmpty) moveIds else {
      lotKey match {
        case "pilot"  => ctx.scenario.waferIds.take(1).flatMap(ctx.waferUUIDs.get).toSet
        case "sample" => ctx.scenario.waferIds.take(2).flatMap(ctx.waferUUIDs.get).toSet
        case "scrap"  =>
          state.wafers.filter(_._2.classification.contains("SCRAP")).keys.flatMap(ctx.waferUUIDs.get).toSet
        case _ => Set.empty[Id]
      }
    }
    val finalMoveNames = if (moveNames.nonEmpty) moveNames else {
      lotKey match {
        case "pilot" => ctx.scenario.waferIds.take(1).toSet
        case "sample" => ctx.scenario.waferIds.take(2).toSet
        case "scrap" => state.wafers.filter(_._2.classification.contains("SCRAP")).keys.toSet
        case _ => Set.empty[String]
      }
    }
    val sagaId = s"SAGA-SPLIT-$lotKey-${state.iteration}"
    val rwkLotName = s"${ctx.scenario.scenarioId}-${lotKey.toUpperCase}"
    ctx.publisher(SagaOperationEvent(sagaId, "SplitLot", "PREPARE", ctx.scenario.scenarioId, rwkLotName, finalMoveIds.toSeq.map(_.toString)))

    // Create child lot first, then execute TCC transfer (ignoring create result — fails safely if already exists)
    createChild.flatMap(_ =>
      ctx.sagaTx(ctx.sourceLotId, childLotId, finalMoveIds, finalMoveNames, None)
    )(ctx.ec).flatMap { confirmation =>
      if (confirmation.error.isEmpty) {
        ctx.publisher(SagaOperationEvent(sagaId, "SplitLot", "COMMITTED", ctx.scenario.scenarioId, rwkLotName, finalMoveIds.toSeq.map(_.toString)))
        val updatedWafers = state.wafers.map { case (wid, info) =>
          if (finalMoveIds.contains(ctx.waferUUIDs.getOrElse(wid, UUID.nameUUIDFromBytes("none".getBytes))))
            wid -> info.copy(subLot = Some(lotKey))
          else wid -> info
        }
        val finalState = s.copy(wafers = updatedWafers, ledgerSeq = s.ledgerSeq + 1, childLotView = Map(lotKey -> ("Active", finalMoveIds.size)))
        Future.successful(finalState)
      } else {
        val errMsg = confirmation.error.getOrElse("unknown")
        ctx.publisher(SagaOperationEvent(sagaId, "SplitLot", s"FAILED: $errMsg", "", "", Seq.empty))
        Future.failed(new IllegalStateException(s"Saga $sagaId SplitLot failed: $errMsg"))
      }
    }(ctx.ec)
  }

  private def sagaMerge(state: FabDemoState, ctx: FabDemoContext, lotKey: String): Future[FabDemoState] = {
    val s = PipelineStages.emitLedger(state, s"PhaseMerge: Saga Merge ← $lotKey", ctx)
    ctx.publisher(GlobalStatusChanged("MERGING", s"Saga Merge ← $lotKey", "PhaseMerge"))
    val childLotId = ctx.childLotIds.getOrElse(lotKey, ctx.reworkLotId)
    val waferEntries = state.wafers.filter { case (_, info) => info.subLot.contains(lotKey) }
    val moveIds = waferEntries.flatMap { case (wid, _) => ctx.waferUUIDs.get(wid) }.toSet
    val moveNames = waferEntries.keys.toSet
    val (finalMoveIds, finalMoveNames) = if (moveIds.nonEmpty) (moveIds, moveNames) else {
      lotKey match {
        case "pilot"  => (ctx.scenario.waferIds.take(1).flatMap(ctx.waferUUIDs.get).toSet, ctx.scenario.waferIds.take(1).toSet)
        case "sample" => (ctx.scenario.waferIds.take(2).flatMap(ctx.waferUUIDs.get).toSet, ctx.scenario.waferIds.take(2).toSet)
        case "hold"   =>
          val hw = state.wafers.filter(_._2.subLot.contains("hold"))
          (hw.flatMap { case (wid, _) => ctx.waferUUIDs.get(wid) }.toSet, hw.keys.toSet)
        case _ => (Set.empty[Id], Set.empty[String])
      }
    }
    val sagaId = s"SAGA-MERGE-$lotKey-${state.iteration}"
    val rwkLotName = s"${ctx.scenario.scenarioId}-${lotKey.toUpperCase}"
    ctx.publisher(SagaOperationEvent(sagaId, "MergeLot", "PREPARE", rwkLotName, ctx.scenario.scenarioId, finalMoveIds.toSeq.map(_.toString)))
    ctx.sagaTx(childLotId, ctx.sourceLotId, finalMoveIds, finalMoveNames, None).flatMap { confirmation =>
      if (confirmation.error.isEmpty) {
        ctx.publisher(SagaOperationEvent(sagaId, "MergeLot", "COMMITTED", rwkLotName, ctx.scenario.scenarioId, finalMoveIds.toSeq.map(_.toString)))
        val mergedWafers = state.wafers.map { case (wid, info) =>
          if (finalMoveIds.contains(ctx.waferUUIDs.getOrElse(wid, java.util.UUID.nameUUIDFromBytes("none".getBytes))))
            wid -> info.copy(subLot = None, classification = Some("PASS"))
          else wid -> info
        }
        val finalState = s.copy(wafers = mergedWafers, ledgerSeq = s.ledgerSeq + 1, childLotView = Map(lotKey -> ("Merged", 0)))
        Future.successful(finalState)
      } else {
        val errMsg = confirmation.error.getOrElse("unknown")
        ctx.publisher(SagaOperationEvent(sagaId, "MergeLot", s"FAILED: $errMsg", rwkLotName, ctx.scenario.scenarioId, moveIds.toSeq.map(_.toString)))
        Future.failed(new IllegalStateException(s"Saga $sagaId MergeLot failed: $errMsg"))
      }
    }(ctx.ec)
  }

  private def scrapWafers(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    val s = PipelineStages.emitLedger(state, "PhaseScrap: Scrap classified wafers", ctx)
    state.wafers.filter(_._2.classification.contains("SCRAP")).keys.foreach { wid =>
      ctx.publisher(ScrapEvent(wid, "CD out of spec → SCRAP"))
    }
    Future.successful(s.copy(ledgerSeq = s.ledgerSeq + 1))
  }

  private def holdWafers(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    val s = PipelineStages.emitLedger(state, "PhaseHold: Hold for engineering review", ctx)
    ctx.publisher(GlobalStatusChanged("HOLDING", "Engineering review", "PhaseHold"))
    val holdIds = state.wafers.filter(_._2.subLot.contains("hold")).keys.toSet
    ctx.lotRef ! RecordWafersHeld(holdIds, "Borderline CD", ctx.ignoreLotReply)
    ctx.publisher(FoupStateChanged(ctx.foupId, "HELD", PipelineStages.activeCount(state), holdIds.size, "CDSEM",
      lotId = ctx.scenario.scenarioId, reworkLotId = s"${ctx.scenario.scenarioId}-HLD"))
    Future.successful(s.copy(ledgerSeq = s.ledgerSeq + 1))
  }

  private def releaseWafers(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    val s = PipelineStages.emitLedger(state, "PhaseRelease: Release held wafers", ctx)
    ctx.publisher(GlobalStatusChanged("RELEASING", "Review passed, releasing", "PhaseRelease"))
    val holdIds = state.wafers.filter(_._2.subLot.contains("hold")).keys.toSet
    ctx.lotRef ! RecordWafersReleased(holdIds, ctx.ignoreLotReply)
    Future.successful(s.copy(ledgerSeq = s.ledgerSeq + 1, reviewApproved = true))
  }

  private def postReleaseClassify(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    val s = PipelineStages.emitLedger(state, "PhasePostReleaseClassify: Classify released wafers as PASS", ctx)
    ctx.publisher(GlobalStatusChanged("CLASSIFYING", "Post-release classification", "PhasePostReleaseClassify"))
    state.wafers.filter { case (_, info) =>
      info.classification.contains("PASS") && info.subLot.isEmpty
    }.foreach { case (wid, info) =>
      ctx.waferUUIDs.get(wid).foreach { uuid =>
        val cdValue = info.cdValueHistory.lastOption.getOrElse(32.0)
        ctx.lotRef ! RecordWaferClassified(uuid, "PASS", info.reworkCount, cdValue, ctx.ignoreLotReply)
      }
    }
    Future.successful(s.copy(ledgerSeq = s.ledgerSeq + 1))
  }

  private def waitForReview(state: FabDemoState, ctx: FabDemoContext, durationMs: Long): Future[FabDemoState] = {
    val s = PipelineStages.emitLedger(state, s"PhaseReview: Engineer review (${durationMs / 1000}s)", ctx)
    ctx.publisher(OrchestratorCommand(PipelineStages.cmdId(), "ENGINEER-REVIEW", "Review",
      s"Reviewing held wafers (${durationMs / 1000}s)", state.wafers.filter(_._2.subLot.contains("hold")).keys.toSeq))
    Future { Thread.sleep(durationMs); s.copy(ledgerSeq = s.ledgerSeq + 1) }(ctx.ec)
  }

  // ====================================================================
  // M3.5 OCAP Interceptor (Hard 3)
  // ====================================================================

  /** Invoked from runSequence.recoverWith when a stage fails.
   *  Evaluates OCAP rules and executes the highest-priority action plan. */
  private[chain] def invokeOcapInterceptor(
    state: FabDemoState, ctx: FabDemoContext, err: StageError
  ): Future[FabDemoState] = {
    val rules = ctx.ocapRules
    if (rules.isEmpty) {
      ctx.lotRef ! FailLot(err.detail, err.stageName, ctx.ignoreLotReply)
      Future.successful(state) // no rules — Phase 1 behavior
    } else {
      OcapEngine.matchRules(state, rules).headOption match {
        case Some(rule) =>
          val s = PipelineStages.emitLedger(state, s"OCAP: Intercepted ${err.stageName} failure — ${rule.name}", ctx)
          ctx.publisher(GlobalStatusChanged("OCAP_INTERCEPT", s"${err.stageName} failed: ${err.detail} → ${rule.name}", "PhaseOCAP"))
          ctx.publisher(OcapActionTriggered(
            ruleId = rule.ruleId, ruleName = rule.name,
            actionType = actionTypeName(rule.actionPlan),
            detail = s"${err.stageName}: ${err.detail}",
            affectedWafers = Seq.empty))
          executeOcapAction(s, ctx, rule.actionPlan)
        case None =>
          ctx.lotRef ! FailLot(err.detail, err.stageName, ctx.ignoreLotReply)
          Future.successful(state) // no rules triggered
      }
    }
  }

  /** Execute a single OCAP action plan, potentially injecting pipeline stages. */
  private[chain] def executeOcapAction(
    state: FabDemoState, ctx: FabDemoContext, actionPlan: OcapActionPlan
  ): Future[FabDemoState] = {
    implicit val ec: ExecutionContext = ctx.ec
    actionPlan match {
      case r: OcapRework =>
        executeSubProcess(state, ctx, SubProcessRef(
          nodeId = "ocap-rework", label = "OCAP Rework", subProcessType = ReworkLoop,
          params = Map("reworkRecipeId" -> r.recipeId, "maxReworkCount" -> r.maxCount.toString)))

      case s: OcapScrap =>
        ctx.publisher(GlobalStatusChanged("SCRAPPING", "OCAP Scrap action", "PhaseOCAP"))
        ctx.lotRef ! FailLot(s.reason, "OCAP_SCRAP", ctx.ignoreLotReply)
        runSequence(Seq(ScrapWafers, SealComplete), state, ctx)

      case h: OcapHold =>
        ctx.publisher(GlobalStatusChanged("HOLDING", s"OCAP Hold: ${h.reason}", "PhaseOCAP"))
        runSequence(Seq(HoldWafers, WaitForReview(h.durationMs), ReleaseWafers), state, ctx)

      case n: OcapNotify =>
        ctx.publisher(GlobalStatusChanged("NOTIFIED", n.reason, "PhaseOCAP"))
        Future.successful(state)

      case a: OcapAdjustRecipe =>
        ctx.publisher(GlobalStatusChanged("ADJUSTED", s"Recipe ${a.recipeId} offset ${a.offsetNm}nm", "PhaseOCAP"))
        Future.successful(state)

      case OcapComposite(actions) =>
        actions.foldLeft(Future.successful(state)) { (f, a) =>
          f.flatMap(s => executeOcapAction(s, ctx, a))
        }
    }
  }

  private def actionTypeName(plan: OcapActionPlan): String = plan match {
    case _: OcapRework       => "REWORK"
    case _: OcapScrap        => "SCRAP"
    case _: OcapHold         => "HOLD"
    case _: OcapNotify       => "NOTIFY"
    case _: OcapAdjustRecipe => "ADJUST_RECIPE"
    case _: OcapComposite    => "COMPOSITE"
  }

  // ====================================================================
  // Unified IR extensions (M3.5+)
  // ====================================================================

  /** Sequential OCAP evaluation (compiled from RouteDefinition OcapFlow/OcapNode). */
  private def ocapEvaluate(state: FabDemoState, ctx: FabDemoContext, rules: List[OcapRuleDefinition]): Future[FabDemoState] = {
    val s = PipelineStages.emitLedger(state, "PhaseOCAP: Evaluating OCAP rules", ctx)
    ctx.publisher(GlobalStatusChanged("OCAP", s"Evaluating ${rules.size} OCAP rule(s)", "PhaseOCAP"))
    OcapEngine.evaluate(s, ctx, rules)(ctx.ec)
  }

  private def executeSubProcess(state: FabDemoState, ctx: FabDemoContext, ref: SubProcessRef, nodeId: Option[String] = None): Future[FabDemoState] = {
    val s = PipelineStages.emitLedger(state, s"PhaseSubProcess: ${ref.subProcessType} (late-binding)", ctx,
      nodeId = nodeId, subProcess = Some(ref.subProcessType.toString))
    ctx.publisher(GlobalStatusChanged("SUB_PROCESS", s"Executing sub-process: ${ref.subProcessType}", "PhaseSubProcess"))
    implicit val ec: ExecutionContext = ctx.ec
    ref.subProcessType match {
      case SendAheadPilot => runPilotSubFlow(s, ctx)
      case ReworkLoop =>
        val recipeId = ref.params.getOrElse("reworkRecipeId", "REWORK-LITHO-001")
        val maxCount = ref.params.get("maxReworkCount").flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(3)
        val needsRework = s.wafers.values.exists(w =>
          w.classification.contains("FAIL") || w.classification.contains("BORDERLINE"))
        if (!needsRework) {
          ctx.publisher(GlobalStatusChanged("SUB_PROCESS", "ReworkLoop: no wafers need rework, skipping", "PhaseSubProcess"))
          Future.successful(s)
        } else {
          val reworkStages: Seq[PipelineStage] = Seq(
            SagaSplit("rwk"),
            Transport("CDSEM", "LITHO"), AtEquipment("LITHO", ctx.scenario.litho.equipmentId),
            TrackIn(ctx.scenario.litho.equipmentId), RunRecipe(ctx.scenario.litho.equipmentId, recipeId),
            TrackOut(ctx.scenario.litho.equipmentId),
            Transport("LITHO", "CDSEM"), AtEquipment("METROLOGY", ctx.scenario.cdSem.equipmentId),
            TrackIn(ctx.scenario.cdSem.equipmentId), Measure(ctx.scenario.cdSem.equipmentId),
            TrackOut(ctx.scenario.cdSem.equipmentId), Classify,
            Branch(
              cond = (st: FabDemoState) => st.wafers.values.exists(w =>
                w.classification.contains("FAIL") || w.classification.contains("BORDERLINE")),
              ifTrue = Seq.empty, // terminal: no more rework (guarded by maxCount in classify)
              ifFalse = Seq(SagaMerge("rwk"))),
            Transport("CDSEM", "STOCKER")
          )
          runSequence(reworkStages, s, ctx)
        }
      case _ => Future.successful(s.copy(ledgerSeq = s.ledgerSeq + 1))
    }
  }
}
