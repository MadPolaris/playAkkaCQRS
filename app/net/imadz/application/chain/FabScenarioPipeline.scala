package net.imadz.application.chain

import akka.util.Timeout
import net.imadz.application.aggregates.LotProtocol.{LotConfirmation, SealLot}
import net.imadz.application.aggregates.LotProtocol._
import net.imadz.common.CommonTypes.Id
import net.imadz.domain.entities.LotEntity.{Active, Completed, HoldSplit, PilotSplit, ReworkSplit, SampleSplit, ScrapSplit, Sealed, SplitReason}
import net.imadz.application.chain.FabExecutionModel.{FabDemoContext, FabDemoState, StageError, StageFailedException, SubLotResult}
import net.imadz.domain.events._

import net.imadz.application.routing.OcapEngine
import net.imadz.domain.routing.{OcapRuleDefinition, ReworkLoop, SendAheadPilot, SubProcessRef,
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

  /** Shared scenario→stages resolution. Single source of truth for static scenario dispatch. */
  def resolveStages(scenarioId: String): Seq[PipelineStage] = scenarioId match {
    case "send-ahead-pilot" => sendAheadStages
    case "scrap-downgrade"  => scrapStages
    case "sampling-demo"    => samplingStages
    case "hold-release"     => holdReleaseStages
    case _                  => basicStages
  }

  /** @deprecated Use [[runStages]] with [[resolveStages]] instead. */
  def runPipeline(initialState: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] =
    runStages(resolveStages(ctx.scenario.scenarioId), initialState, ctx)

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
            ctx.stageProgress("FAILED", s"${err.stageName}: ${err.detail}", "PhaseFailed")
            invokeOcapInterceptor(state, ctx, err)
          case ex: Exception =>
            ctx.stageProgress("ERROR", s"Unexpected: ${ex.getMessage}", "PhaseFailed")
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
  /** M3.5 Self-Healing Demo classify stage with OCAP event publishing.
    * Runs standard classification, then evaluates OCAP rules against
    * measurement results and publishes OcapActionTriggered events. */
  case class M35ClassifyWithOcap(rules: List[OcapRuleDefinition]) extends PipelineStage

  /** Executes pending OCAP actions stored in [[FabDemoState.ocapActions]].
    * Placed after [[M35ClassifyWithOcap]] in M3.5 stage lists. */
  case object OcapActionRouter extends PipelineStage

  /** Suspends pipeline execution until a sub-lot (created by [[ExecuteSubProcess(ReworkLoop)]])
    * reaches its terminal outcome. The sub-lot result is delivered via
    * [[FabDemoContext.awaitPromises]]. On "scrapped" outcome, triggers OCAP
    * re-evaluation for remaining parent wafers instead of proceeding normally. */
  case class AwaitSubLotResult(lotKey: String) extends PipelineStage

  // ---- Macro-stage variants for non-Actor path unification (Phase 3) ----

  /** Runs [[FabDemoPipeline.runPipeline]] as a single composite stage.
    * Internal steps still emit UI events via ctx.publisher until Phase 3b. */
  case object PhotoCellReworkPipeline extends PipelineStage

  /** Runs [[FabFlowEngine.runRouting]] as a single composite stage.
    * Internal POR steps still emit UI events via ctx.publisher until Phase 3b. */
  case class DynamicPorExecution(routing: net.imadz.domain.values.Por, spec: net.imadz.application.scenario.DecisionConfig) extends PipelineStage

  def runStage(stage: PipelineStage, state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
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
      case M35ClassifyWithOcap(rules) => m35ClassifyWithOcap(state, ctx, rules)
      case OcapActionRouter => ocapActionRouter(state, ctx)
      case AwaitSubLotResult(lotKey) => awaitSubLotResult(state, ctx, lotKey)
      case PhotoCellReworkPipeline => FabDemoPipeline.runPipeline(state, ctx)
      case DynamicPorExecution(routing, spec) => FabFlowEngine.runRouting(routing, spec)(state, ctx)
    }
  }

  private def areaTypeFor(equipId: String): String =
    if (equipId.contains("LITHO")) "LITHO" else if (equipId.contains("CDSEM")) "METROLOGY" else equipId

  // ====================================================================
  // Scenario recipes
  // ====================================================================

  def basicStages: Seq[PipelineStage] = Seq(
    LoadFoup,
    Transport("STOCKER", "LITHO"), AtEquipment("LITHO", "LITHO-01"),
    TrackIn("LITHO-01"), RunRecipe("LITHO-01", "LITHO-28-001"), TrackOut("LITHO-01"),
    Transport("LITHO", "CDSEM"), AtEquipment("METROLOGY", "CDSEM-01"),
    TrackIn("CDSEM-01"), Measure("CDSEM-01"), TrackOut("CDSEM-01"), Classify,
    Transport("CDSEM", "STOCKER"), SealComplete
  )

  def sendAheadStages: Seq[PipelineStage] = {
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

  def scrapStages: Seq[PipelineStage] = {
    val equipId = "LITHO-01"; val cdSemId = "CDSEM-01"
    Seq(LoadFoup,
      Transport("STOCKER", "LITHO"), AtEquipment("LITHO", equipId),
      TrackIn(equipId), RunRecipe(equipId, "LITHO-28-001"), TrackOut(equipId),
      Transport("LITHO", "CDSEM"), AtEquipment("METROLOGY", cdSemId),
      TrackIn(cdSemId), Measure(cdSemId), TrackOut(cdSemId), Classify,
      SagaSplit("scrap"), ScrapWafers,
      Transport("CDSEM", "STOCKER"), SealComplete)
  }

  def samplingStages: Seq[PipelineStage] = {
    val cdSemId = "CDSEM-01"
    Seq(LoadFoup, SagaSplit("sample"),
      Transport("STOCKER", "CDSEM"), AtEquipment("METROLOGY", cdSemId),
      TrackIn(cdSemId), Measure(cdSemId), TrackOut(cdSemId), Classify,
      SagaMerge("sample"),
      Transport("CDSEM", "STOCKER"), SealComplete)
  }

  def holdReleaseStages: Seq[PipelineStage] = {
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
  // M3.5 Self-Healing Demo Stage Lists (with OCAP evaluation)
  // ====================================================================

  /** M3.5 basic rework scenario stages with OCAP evaluation after classify. */
  def m35BasicStages(rules: List[OcapRuleDefinition]): Seq[PipelineStage] = {
    val equipId = "LITHO-01"; val cdSemId = "CDSEM-01"
    Seq(
      LoadFoup,
      Transport("STOCKER", "LITHO"), AtEquipment("LITHO", equipId),
      TrackIn(equipId), RunRecipe(equipId, "LITHO-28-001"), TrackOut(equipId),
      Transport("LITHO", "CDSEM"), AtEquipment("METROLOGY", cdSemId),
      TrackIn(cdSemId), Measure(cdSemId), TrackOut(cdSemId), Classify,
      M35ClassifyWithOcap(rules),
      OcapActionRouter,
      AwaitSubLotResult("rwk"),
      Transport("CDSEM", "STOCKER"), SealComplete
    )
  }

  /** M3.5 send-ahead scenario stages with OCAP evaluation of pilot wafer. */
  def m35SendAheadStages(rules: List[OcapRuleDefinition]): Seq[PipelineStage] = {
    val equipId = "LITHO-01"; val cdSemId = "CDSEM-01"
    Seq(
      LoadFoup, SagaSplit("pilot"), PilotSubFlow,
      M35ClassifyWithOcap(rules),
      OcapActionRouter,
      Branch(_.pilotPassed,
        Seq(SagaMerge("pilot"),
          Transport("STOCKER", "LITHO"), AtEquipment("LITHO", equipId),
          TrackIn(equipId), RunRecipe(equipId, "LITHO-28-001"), TrackOut(equipId),
          Transport("LITHO", "CDSEM"), AtEquipment("METROLOGY", cdSemId),
          TrackIn(cdSemId), Measure(cdSemId), TrackOut(cdSemId), Classify,
          M35ClassifyWithOcap(rules),
          OcapActionRouter,
          Transport("CDSEM", "STOCKER"), SealComplete),
        Seq(ScrapWafers, SealComplete))
    )
  }

  /** M3.5 multi-WO chaos scenario stages — basic with OCAP, used for each work order. */
  def m35ChaosStages(rules: List[OcapRuleDefinition]): Seq[PipelineStage] =
    m35BasicStages(rules)

  // ====================================================================
  // Scenario-specific stages (not shared with FabDemoPipeline/FabFlowEngine)
  // ====================================================================

  private def classifyStage(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    val s = PipelineStages.emitLedger(state, "PhaseClassify: Decision Engine", ctx)
    ctx.stageProgress("CLASSIFYING", "Decision", "PhaseClassify")
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
    pilotCtx.stageProgress("CLASSIFYING", "Classifying pilot wafer", "PhaseClassify")
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
    ctx.stageProgress("SPLITTING", s"Saga TCC split → $lotKey", "PhaseSplit")
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
        case _ /* rwk / rework keys */ =>
          state.wafers.filter(w => w._2.classification.exists(c => c == "FAIL" || c == "BORDERLINE"))
            .keys.flatMap(ctx.waferUUIDs.get).toSet
      }
    }
    val finalMoveNames = if (moveNames.nonEmpty) moveNames else {
      lotKey match {
        case "pilot" => ctx.scenario.waferIds.take(1).toSet
        case "sample" => ctx.scenario.waferIds.take(2).toSet
        case "scrap" => state.wafers.filter(_._2.classification.contains("SCRAP")).keys.toSet
        case _ /* rwk / rework keys */ =>
          state.wafers.filter(w => w._2.classification.exists(c => c == "FAIL" || c == "BORDERLINE"))
            .keys.toSet
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
        // Domain event: source lot records sub-lot creation
        ctx.lotRef ! RecordSubLotCreated(childLotId, splitReason, finalMoveIds, ctx.ignoreLotReply)
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
    ctx.stageProgress("MERGING", s"Saga Merge ← $lotKey", "PhaseMerge")
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
        case _ /* rwk / rework keys */ =>
          val rwkWafers = state.wafers.filter(w => w._2.classification.exists(c => c == "FAIL" || c == "BORDERLINE"))
          (rwkWafers.flatMap { case (wid, _) => ctx.waferUUIDs.get(wid) }.toSet, rwkWafers.keys.toSet)
      }
    }
    val sagaId = s"SAGA-MERGE-$lotKey-${state.iteration}"
    val rwkLotName = s"${ctx.scenario.scenarioId}-${lotKey.toUpperCase}"
    ctx.publisher(SagaOperationEvent(sagaId, "MergeLot", "PREPARE", rwkLotName, ctx.scenario.scenarioId, finalMoveIds.toSeq.map(_.toString)))
    ctx.sagaTx(childLotId, ctx.sourceLotId, finalMoveIds, finalMoveNames, None).flatMap { confirmation =>
      if (confirmation.error.isEmpty) {
        ctx.publisher(SagaOperationEvent(sagaId, "MergeLot", "COMMITTED", rwkLotName, ctx.scenario.scenarioId, finalMoveIds.toSeq.map(_.toString)))
        // Domain event: source lot records sub-lot merge
        ctx.lotRef ! RecordSubLotMerged(childLotId, finalMoveIds, ctx.ignoreLotReply)
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

  /** Seal a child lot that failed rework. Triggers LotScrapEvent so WorkOrder projection
    * tracks the scrapped wafers correctly — prevents Ghost Lots (sealed but invisible to scheduling).
    * Architect note: per MES domain rules, failed rework must emit an explicit domain event,
    * not just silently seal the lot. */
  private def sealChildLot(state: FabDemoState, ctx: FabDemoContext,
                           childLotRef: akka.cluster.sharding.typed.scaladsl.EntityRef[net.imadz.application.aggregates.LotProtocol.LotCommand],
                           childLotId: Id, maxRework: Int): Future[FabDemoState] = {
    implicit val ec: ExecutionContext = ctx.ec

    val scrappedWafers = state.wafers.filter(w =>
      w._2.classification.exists(c => c == "FAIL" || c == "BORDERLINE"))
    val scrapCount = scrappedWafers.size
    val scrapReason = s"Rework failed after $maxRework attempts: ${scrappedWafers.keys.mkString(",")}"

    ctx.stageProgress("SUB_PROCESS",
      s"ReworkLoop: $scrapReason — scrapping child lot $childLotId", "PhaseSubProcess")

    // Ghost Lot defense: explicit domain event so WorkOrderCompletionProjection sees it
    import net.imadz.application.aggregates.LotProtocol.{FailLot, SealLot}
    childLotRef ! FailLot(scrapReason, "OCAP_SCRAP", ctx.ignoreLotReply)
    childLotRef ! SealLot(ctx.ignoreLotReply)

    // Record SubLotScrapped on the PARENT lot so it transitions from AwaitingSubLot → Active
    ctx.lotRef ! RecordSubLotScrapped(childLotId, scrapReason,
      scrappedWafers.flatMap { case (wid, _) => ctx.waferUUIDs.get(wid) }.toSet, ctx.ignoreLotReply)

    val updatedState = state.copy(
      scrapCount = state.scrapCount + scrapCount,
      ledgerSeq = state.ledgerSeq + 1,
      childLotView = state.childLotView + ("rwk" -> ("Scrapped", scrapCount))
    )
    Future.successful(updatedState)
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
    ctx.stageProgress("HOLDING", "Engineering review", "PhaseHold")
    val holdIds = state.wafers.filter(_._2.subLot.contains("hold")).keys.toSet
    ctx.lotRef ! RecordWafersHeld(holdIds, "Borderline CD", ctx.ignoreLotReply)
    ctx.publisher(FoupStateChanged(ctx.foupId, "HELD", PipelineStages.activeCount(state), holdIds.size, "CDSEM",
      lotId = ctx.scenario.scenarioId, reworkLotId = s"${ctx.scenario.scenarioId}-HLD"))
    Future.successful(s.copy(ledgerSeq = s.ledgerSeq + 1))
  }

  private def releaseWafers(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    val s = PipelineStages.emitLedger(state, "PhaseRelease: Release held wafers", ctx)
    ctx.stageProgress("RELEASING", "Review passed, releasing", "PhaseRelease")
    val holdIds = state.wafers.filter(_._2.subLot.contains("hold")).keys.toSet
    ctx.lotRef ! RecordWafersReleased(holdIds, ctx.ignoreLotReply)
    Future.successful(s.copy(ledgerSeq = s.ledgerSeq + 1, reviewApproved = true))
  }

  private def postReleaseClassify(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    val s = PipelineStages.emitLedger(state, "PhasePostReleaseClassify: Classify released wafers as PASS", ctx)
    ctx.stageProgress("CLASSIFYING", "Post-release classification", "PhasePostReleaseClassify")
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
  // M3.5 Self-Healing: Classify + OCAP Event Publishing
  // ====================================================================

  /**
   * M3.5 classify stage that runs standard classification and then evaluates
   * OCAP rules against measurement results. Publishes OcapActionTriggered
   * events for triggered rules but does NOT execute action plans (the
   * hardcoded pipeline handles rework/split/merge). This gives the UI
   * visible OCAP rule firings without duplicating pipeline logic.
   */
  private def m35ClassifyWithOcap(state: FabDemoState, ctx: FabDemoContext, rules: List[OcapRuleDefinition]): Future[FabDemoState] = {
    implicit val ec: ExecutionContext = ctx.ec

    // First, run OCAP evaluation to check conditions and publish events
    if (rules.nonEmpty) {
      ocapEvaluate(state, ctx, rules).flatMap { ocapState =>
        // Then run standard classify
        classifyStage(ocapState, ctx)
      }
    } else {
      classifyStage(state, ctx)
    }
  }

  // ====================================================================
  // M3.5 OCAP Interceptor
  // ====================================================================

  /** Reads [[FabDemoState.ocapActions]] and executes the highest-priority action.
    * Proactive counterpart to [[invokeOcapInterceptor]] — runs after classification,
    * not just on stage failure. If no pending actions, returns state unchanged. */
  private def ocapActionRouter(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    implicit val ec: ExecutionContext = ctx.ec
    state.ocapActions.headOption match {
      case Some((ruleId, actionPlan)) =>
        val s = PipelineStages.emitLedger(state,
          s"PhaseOcapAction: Executing OCAP rule $ruleId: ${actionTypeName(actionPlan)}", ctx)
        ctx.stageProgress("OCAP_ACTION",
          s"Rule $ruleId: ${actionTypeName(actionPlan)}", "PhaseOcapAction")
        executeOcapAction(s.copy(ocapActions = Nil), ctx, actionPlan)
      case None =>
        Future.successful(state)
    }
  }

  /** Invoked from runSequence.recoverWith when a stage fails.
   *  Evaluates OCAP rules and executes the highest-priority action plan. */
  def invokeOcapInterceptor(
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
          ctx.stageProgress("OCAP_INTERCEPT", s"${err.stageName} failed: ${err.detail} → ${rule.name}", "PhaseOCAP")
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
  def executeOcapAction(
    state: FabDemoState, ctx: FabDemoContext, actionPlan: OcapActionPlan
  ): Future[FabDemoState] = {
    implicit val ec: ExecutionContext = ctx.ec
    actionPlan match {
      case r: OcapRework =>
        executeSubProcess(state, ctx, SubProcessRef(
          nodeId = "ocap-rework", label = "OCAP Rework", subProcessType = ReworkLoop,
          params = Map("reworkRecipeId" -> r.recipeId, "maxReworkCount" -> r.maxCount.toString)))

      case s: OcapScrap =>
        ctx.stageProgress("SCRAPPING", "OCAP Scrap action", "PhaseOCAP")
        ctx.lotRef ! FailLot(s.reason, "OCAP_SCRAP", ctx.ignoreLotReply)
        runSequence(Seq(ScrapWafers, SealComplete), state, ctx)

      case h: OcapHold =>
        ctx.stageProgress("HOLDING", s"OCAP Hold: ${h.reason}", "PhaseOCAP")
        runSequence(Seq(HoldWafers, WaitForReview(h.durationMs), ReleaseWafers), state, ctx)

      case n: OcapNotify =>
        ctx.stageProgress("NOTIFIED", n.reason, "PhaseOCAP")
        Future.successful(state)

      case a: OcapAdjustRecipe =>
        ctx.stageProgress("ADJUSTED", s"Recipe ${a.recipeId} offset ${a.offsetNm}nm", "PhaseOCAP")
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
    ctx.stageProgress("OCAP", s"Evaluating ${rules.size} OCAP rule(s)", "PhaseOCAP")
    OcapEngine.evaluate(s, ctx, rules)(ctx.ec)
  }

  /** SubLot alternate route: independent manufacturing steps for rework.
    * Does NOT include SagaSplit/SagaMerge/Branch — those are orchestration concerns. */
  private def reworkAlternateRoute(equipId: String, recipeId: String, measureEquipId: String): Seq[PipelineStage] =
    Seq(
      Transport("CDSEM", "LITHO"), AtEquipment("LITHO", equipId),
      TrackIn(equipId), RunRecipe(equipId, recipeId), TrackOut(equipId),
      Transport("LITHO", "CDSEM"), AtEquipment("METROLOGY", measureEquipId),
      TrackIn(measureEquipId), Measure(measureEquipId), TrackOut(measureEquipId),
      Classify
    )

  private def executeSubProcess(state: FabDemoState, ctx: FabDemoContext, ref: SubProcessRef, nodeId: Option[String] = None): Future[FabDemoState] = {
    val s = PipelineStages.emitLedger(state, s"PhaseSubProcess: ${ref.subProcessType} (late-binding)", ctx,
      nodeId = nodeId, subProcess = Some(ref.subProcessType.toString))
    ctx.stageProgress("SUB_PROCESS", s"Executing sub-process: ${ref.subProcessType}", "PhaseSubProcess")
    implicit val ec: ExecutionContext = ctx.ec
    ref.subProcessType match {
      case SendAheadPilot => runPilotSubFlow(s, ctx)
      case ReworkLoop =>
        val recipeId = ref.params.getOrElse("reworkRecipeId", "REWORK-LITHO-001")
        val maxCount = ref.params.get("maxReworkCount").flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(3)
        val needsRework = s.wafers.values.exists(w =>
          w.classification.contains("FAIL") || w.classification.contains("BORDERLINE"))
        if (!needsRework) {
          ctx.stageProgress("SUB_PROCESS", "ReworkLoop: no wafers need rework, skipping", "PhaseSubProcess")
          Future.successful(s)
        } else {
          val childLotRef = ctx.childLotRefs.getOrElse("rwk", ctx.reworkLotRef)
          val childLotId = ctx.childLotIds.getOrElse("rwk", ctx.reworkLotId)
          val childCtx = ctx.copy(lotRef = childLotRef)
          val altRoute = reworkAlternateRoute(ctx.scenario.litho.equipmentId, recipeId, ctx.scenario.cdSem.equipmentId)

          // Phase A: sagaSplit synchronously, then launch background processing
          sagaSplit(s, ctx, "rwk").flatMap { splitState =>
            val resetState = splitState.copy(wafers = splitState.wafers.map { case (wid, info) =>
              if (info.subLot.contains("rwk")) wid -> info.copy(classification = None)
              else wid -> info
            })

            // Store Promise for AwaitSubLotResult stage
            val promise = scala.concurrent.Promise[SubLotResult]()
            ctx.awaitPromises.put("rwk", promise)

            // Launch rework sub-process asynchronously in the background
            launchReworkSubProcess(resetState, ctx, childCtx, childLotRef, childLotId,
              altRoute, maxCount, promise)

            // Return immediately — pipeline continues to AwaitSubLotResult
            Future.successful(resetState)
          }(ctx.ec)
        }
      case _ => Future.successful(s.copy(ledgerSeq = s.ledgerSeq + 1))
    }
  }

  /** Launch rework sub-process in background and resolve the parent's Promise on completion.
   *  Called from [[executeSubProcess(ReworkLoop)]] after sagaSplit. */
  private def launchReworkSubProcess(
    state: FabDemoState, parentCtx: FabDemoContext, childCtx: FabDemoContext,
    childLotRef: akka.cluster.sharding.typed.scaladsl.EntityRef[LotCommand], childLotId: Id,
    altRoute: Seq[PipelineStage], maxCount: Int,
    promise: scala.concurrent.Promise[SubLotResult]
  ): Unit = {
    implicit val ec: ExecutionContext = parentCtx.ec
    val future = for {
      sublotState <- runSequence(altRoute, state, childCtx)
      stillFailing = sublotState.wafers.values.exists(w =>
        w.classification.contains("FAIL") || w.classification.contains("BORDERLINE"))
      result <- if (!stillFailing) {
        sagaMerge(sublotState, parentCtx, "rwk").map(_ -> "merged")(ec)
      } else {
        sealChildLot(sublotState, parentCtx, childLotRef, childLotId, maxCount).map(_ -> "scrapped")(ec)
      }
    } yield result

    future.onComplete {
      case scala.util.Success((finalState, outcome)) =>
        promise.trySuccess(SubLotResult(finalState, outcome))
      case scala.util.Failure(ex) =>
        promise.tryFailure(ex)
    }
  }

  /** Await the result of an asynchronous sub-lot processing.
   *  The Promise is stored in [[FabDemoContext.awaitPromises]] by [[executeSubProcess]].
   *  On "scrapped" outcome, triggers OCAP re-evaluation for remaining parent wafers. */
  private def awaitSubLotResult(state: FabDemoState, ctx: FabDemoContext, lotKey: String): Future[FabDemoState] = {
    implicit val ec: ExecutionContext = ctx.ec

    ctx.awaitPromises.get(lotKey) match {
      case Some(promise) =>
        // Normal path: wait for background processing to complete
        ctx.stageProgress("AWAITING_SUBLOT",
          s"Waiting for $lotKey sub-lot outcome", "PhaseAwaitSubLotResult")
        promise.future.map { result =>
          val s = PipelineStages.emitLedger(state,
            s"PhaseAwaitSubLotResult: $lotKey → ${result.outcome}", ctx)
          result.outcome match {
            case "merged" =>
              ctx.stageProgress("SUB_PROCESS",
                s"SubLot $lotKey merged back successfully", "PhaseAwaitSubLotResult")
              result.state.copy(ocapActions = Nil)
            case "scrapped" =>
              ctx.stageProgress("SUB_PROCESS",
                s"SubLot $lotKey scrapped — triggering OCAP re-evaluation", "PhaseAwaitSubLotResult")
              val newActions = reEvaluateOcapForRemainingWafers(result.state, ctx)
              result.state.copy(ocapActions = newActions)
          }
        }(ec)

      case None =>
        // Crash recovery path: Promise lost, check child lot state
        val childLotRef = ctx.childLotRefs.getOrElse(lotKey, ctx.reworkLotRef)
        implicit val timeout: akka.util.Timeout = akka.util.Timeout(10, scala.concurrent.duration.SECONDS)
        childLotRef.ask[LotConfirmation](ref => GetLotState(ref)).flatMap { childState =>
          childState.phase match {
            case Some(Sealed) =>
              ctx.stageProgress("AWAITING_SUBLOT",
                s"Crash recovery: $lotKey sub-lot was scrapped", "PhaseAwaitSubLotResult")
              val newActions = reEvaluateOcapForRemainingWafers(state, ctx)
              Future.successful(state.copy(ocapActions = newActions))
            case Some(Completed) | Some(Active) if childState.waferIds.isEmpty =>
              ctx.stageProgress("AWAITING_SUBLOT",
                s"Crash recovery: $lotKey sub-lot already merged", "PhaseAwaitSubLotResult")
              Future.successful(state.copy(ocapActions = Nil))
            case _ =>
              // Crash recovery: child lot still active. Check wafer classifications
              // to determine rework outcome without waiting on a lost background Future.
              val wafers = childState.waferIds
              val classifications = childState.waferClassifications
              val allClassified = wafers.nonEmpty && wafers.forall(classifications.contains)
              if (allClassified) {
                val hasFailed = classifications.values.exists(c => c == "FAIL" || c == "SCRAP")
                if (hasFailed) {
                  ctx.stageProgress("AWAITING_SUBLOT",
                    s"Crash recovery: $lotKey sub-lot rework failed, scrapping", "PhaseAwaitSubLotResult")
                  val newActions = reEvaluateOcapForRemainingWafers(state, ctx)
                  Future.successful(state.copy(ocapActions = newActions))
                } else {
                  ctx.stageProgress("AWAITING_SUBLOT",
                    s"Crash recovery: $lotKey sub-lot rework passed, merging", "PhaseAwaitSubLotResult")
                  Future.successful(state.copy(ocapActions = Nil))
                }
              } else {
                // Wafers not yet classified — rework was still in-flight at crash time.
                // Re-arm the wait and re-launch background processing.
                ctx.stageProgress("AWAITING_SUBLOT",
                  s"Crash recovery: $lotKey sub-lot still active — re-arming wait", "PhaseAwaitSubLotResult")
                val p = scala.concurrent.Promise[SubLotResult]()
                ctx.awaitPromises.put(lotKey, p)
                p.future.map { result =>
                  val newActions = if (result.outcome == "scrapped")
                    reEvaluateOcapForRemainingWafers(result.state, ctx)
                  else Nil
                  result.state.copy(ocapActions = newActions)
                }(ec)
              }
          }
        }(ec)
    }
  }

  /** After a sub-lot is scrapped, re-evaluate OCAP rules for remaining parent wafers.
   *  This is the compensation path — instead of proceeding to Transport→SealComplete,
   *  the parent lot's remaining wafers are re-assessed for scrap/downgrade. */
  private def reEvaluateOcapForRemainingWafers(state: FabDemoState, ctx: FabDemoContext): List[(String, OcapActionPlan)] = {
    // Only consider wafers that are still in the parent lot (not in any sub-lot)
    val remainingWafers = state.wafers.filter { case (_, info) =>
      info.subLot.isEmpty && !info.classification.contains("SCRAP")
    }
    if (remainingWafers.isEmpty || ctx.ocapRules.isEmpty) return Nil

    val remainingState = state.copy(
      wafers = remainingWafers,
      passCount = remainingWafers.count(_._2.classification.contains("PASS")),
      scrapCount = remainingWafers.count(_._2.classification.contains("SCRAP"))
    )
    OcapEngine.matchRules(remainingState, ctx.ocapRules) match {
      case matched :: _ =>
        ctx.publisher(OcapActionTriggered(
          ruleId = matched.ruleId, ruleName = matched.name,
          actionType = actionTypeName(matched.actionPlan),
          detail = s"OCAP re-evaluation after SubLotScrapped: ${remainingWafers.size} wafers remaining",
          affectedWafers = remainingWafers.keys.toSeq
        ))
        List(matched.ruleId -> matched.actionPlan)
      case Nil => Nil
    }
  }
}
