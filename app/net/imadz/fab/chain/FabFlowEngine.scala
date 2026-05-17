package net.imadz.fab.chain

import akka.util.Timeout
import net.imadz.application.aggregates.LotProtocol.{LotConfirmation, SealLot}
import net.imadz.application.aggregates.LotProtocol._
import net.imadz.common.CommonTypes.Id
import net.imadz.domain.entities.LotEntity.{ReworkSplit, ScrapSplit}
import net.imadz.fab.model.FabExecutionModel.{FabDemoContext, FabDemoState, WaferInfo}
import net.imadz.fab.events._
import net.imadz.fab.model.{EquipmentArea, Por, PorStep}
import net.imadz.fab.protocol.{ProcessRecipe, TransferFoup}
import net.imadz.fab.scenario.DecisionConfig

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

/**
 * Dynamic flow engine — executes a [[Por]] step by step,
 * delegating runtime decisions to [[DynamicFlowAssembler]].
 *
 * Stage functions delegate to [[PipelineStages]] for shared logic;
 * only dynamic routing (while-loop + DynamicFlowAssembler) remains here.
 */
object FabFlowEngine {

  /** Default spec for dynamic routing (28nm node) */
  val DefaultDecisionConfig = DecisionConfig(
    lowerSpecNm = 28.0,
    upperSpecNm = 34.0,
    borderlineWindowNm = 2.0,
    maxReworkCount = 3,
    reworkRecipeId = "REWORK-LITHO-001"
  )

  /** Equipment area → equipment ID mapping (simulation) */
  val AreaToEquipmentId: Map[String, String] = Map(
    "CLEAN" -> "CLEAN-01", "DIFF" -> "DIFF-01", "LITHO" -> "LITHO-01",
    "ETCH"  -> "ETCH-01",  "IMPL" -> "IMPL-01", "DEP"   -> "DEP-01",
    "CMP"   -> "CMP-01",   "MET"  -> "CDSEM-01", "DRY"   -> "DRY-01",
    "LOG"   -> "LOG-01"
  )

  val CdsemEquipId = "CDSEM-01"
  val StockerEquipId = "STOCKER-01"

  /** Steps requiring CD measurement after processing */
  val MeasureAreas: Set[String] = Set("LITHO", "ETCH", "MET")

  /** Default AMHS routes (all area pairs connect with 2s transport) */
  val DefaultRoutes: Map[(String, String), FiniteDuration] = {
    val areaIds = Seq("STOCKER") ++ EquipmentArea.all.map(_.areaId)
    (for { from <- areaIds; to <- areaIds; if from != to } yield (from, to) -> 2.seconds).toMap
  }

  // ============================================================================
  // Main entry point
  // ============================================================================

  def runRouting(routing: Por, spec: DecisionConfig = DefaultDecisionConfig)(
    initialState: FabDemoState, ctx: FabDemoContext
  ): Future[FabDemoState] = {
    implicit val ec: ExecutionContext = ctx.ec
    val init = initialState.copy(
      currentRoutingStep = 0,
      routingStepReentry = initialState.routingStepReentry,
      areaVisitHistory = initialState.areaVisitHistory
    )
    for {
      s1 <- loadFoup(init, ctx, routing)
      s2 <- executeSteps(s1, ctx, routing, spec)
      s3 <- transportToStocker(s2, ctx, routing)
      s4 <- sealComplete(s3, ctx, routing)
    } yield s4
  }

  // ============================================================================
  // Step loop
  // ============================================================================

  private def executeSteps(
    state: FabDemoState, ctx: FabDemoContext, routing: Por, spec: DecisionConfig
  ): Future[FabDemoState] = {
    implicit val ec: ExecutionContext = ctx.ec

    def loop(s: FabDemoState): Future[FabDemoState] = {
      if (s.currentRoutingStep >= routing.steps.length) Future.successful(s)
      else {
        val step = routing.steps(s.currentRoutingStep)
        val prevAreaId = s.areaVisitHistory.lastOption.getOrElse("STOCKER")
        val targetAreaId = step.equipmentArea.areaId
        val equipId = AreaToEquipmentId.getOrElse(targetAreaId, s"$targetAreaId-01")
        val reentryIdx = DynamicFlowAssembler.calculateReentryIndex(targetAreaId, s.areaVisitHistory)

        PipelineStages.emitLedger(s, s"Step ${s.currentRoutingStep + 1}/${routing.steps.length}: $targetAreaId (reentry=$reentryIdx) — ${step.recipeId}", ctx)

        for {
          s1 <- PipelineStages.transport(s, ctx, prevAreaId, targetAreaId)
          s2 <- PipelineStages.atEquipment(s1, ctx, targetAreaId, equipId)
          s2b <- PipelineStages.trackIn(s2, ctx, equipId)
          s3 <- PipelineStages.process(s2b, ctx, equipId, step.recipeId, targetAreaId)
          s3b <- PipelineStages.trackOut(s3, ctx, equipId)
          s4 <- if (MeasureAreas.contains(targetAreaId)) {
            measureAndClassify(s3b, ctx, step, routing, spec)
          } else {
            val next = s3.copy(
              currentRoutingStep = s3.currentRoutingStep + 1,
              areaVisitHistory = s3.areaVisitHistory :+ targetAreaId,
              routingStepReentry = s3.routingStepReentry + (targetAreaId -> (reentryIdx + 1)),
              ledgerSeq = s3.ledgerSeq + 1
            )
            ctx.publisher(LedgerStepAdvanced(s3.ledgerSeq, s"Step ${s3.currentRoutingStep + 1}/${routing.steps.length}: $targetAreaId — no measurement, auto-advance"))
            Future.successful(next)
          }
          s5 <- loop(s4)
        } yield s5
      }
    }

    loop(state)
  }

  // ============================================================================
  // Dynamic-specific stages
  // ============================================================================

  private def loadFoup(state: FabDemoState, ctx: FabDemoContext, routing: Por): Future[FabDemoState] = {
    val s = PipelineStages.emitLedger(state, s"PhaseLoad: Load FOUP — ${routing.productId} (${routing.steps.size} steps)", ctx)
    ctx.publisher(GlobalStatusChanged("LOADING", s"Starting ${routing.productId}", "PhaseLoad"))
    ctx.lotRef ! RecordFoupLoaded(ctx.foupId, StockerEquipId, ctx.ignoreLotReply)
    ctx.publisher(FoupStateChanged(ctx.foupId, "LOADING", PipelineStages.activeCount(state), 0, "STOCKER", lotId = routing.productId))
    ctx.publisher(FoupArrivedAtPort(ctx.foupId, StockerEquipId, "STOCKER-PORT-1"))
    ctx.publisher(EquipmentStateChanged(StockerEquipId, "STOCKER", "Idle", None))
    Future.successful(s.copy(ledgerSeq = s.ledgerSeq + 1, currentArea = "STOCKER"))
  }

  private def measureAndClassify(
    state: FabDemoState, ctx: FabDemoContext, step: PorStep,
    routing: Por, spec: DecisionConfig
  ): Future[FabDemoState] = {
    implicit val ec: ExecutionContext = ctx.ec
    val targetAreaId = step.equipmentArea.areaId
    val reentryIdx = state.routingStepReentry.getOrElse(targetAreaId, 0)
    val scaledMs = (5000L / ctx.speedMultiplier).toLong

    for {
      s1 <- PipelineStages.transport(state, ctx, targetAreaId, "MET")
      s2 <- PipelineStages.atEquipment(s1, ctx, "MET", CdsemEquipId)
      s2b <- PipelineStages.trackIn(s2, ctx, CdsemEquipId)
      s3 = PipelineStages.emitLedger(s2b, s"Measure: CD-SEM — ${step.recipeId}", ctx)
      _ = {
        ctx.publisher(GlobalStatusChanged("MEASURING", "CD measurement", "PhaseMeasure"))
        ctx.lotRef ! RecordEquipmentJobStarted(CdsemEquipId, "CD-MEASURE-001", ctx.ignoreLotReply)
        ctx.publisher(EquipmentStateChanged(CdsemEquipId, "MET", "Busy", Some("metrology-job")))
        ctx.publisher(ProcessingStarted(CdsemEquipId, "CD-MEASURE-001", scaledMs))
      }
      result <- ctx.adapter.sendCommand(CdsemEquipId, ProcessRecipe("CD-MEASURE-001"))
      s4 <- result match {
        case net.imadz.fab.protocol.JobCompleted(jobId, _, net.imadz.fab.protocol.MetrologyResult(_, waferMeasurements)) =>
          Future.successful {
            ctx.lotRef ! RecordEquipmentJobCompleted(CdsemEquipId, jobId, success = true, ctx.ignoreLotReply)
            ctx.publisher(ProcessingCompleted(CdsemEquipId, jobId, success = true, ""))
            ctx.publisher(EquipmentStateChanged(CdsemEquipId, "MET", "Idle", None))
            val cdValues: Map[String, Double] = waferMeasurements.map { case (wid, cd) => wid -> cd.measuredNm }
            s3.copy(ledgerSeq = s3.ledgerSeq + 1, wafers = s3.wafers.map { case (wid, info) =>
              wid -> info.copy(cdValueHistory = info.cdValueHistory ++ cdValues.get(wid).toList)
            })
          }
        case _ => Future.successful(s3.copy(ledgerSeq = s3.ledgerSeq + 1))
      }
      s5 <- classifyAndDecide(s4, ctx, step, routing, spec, reentryIdx, targetAreaId)
    } yield s5
  }

  private def classifyAndDecide(
    state: FabDemoState, ctx: FabDemoContext, step: PorStep,
    routing: Por, spec: DecisionConfig,
    reentryIdx: Int, targetAreaId: String
  )(implicit ec: ExecutionContext): Future[FabDemoState] = {
    val s = PipelineStages.emitLedger(state, s"Classify: Decision Engine — ${step.stepId}", ctx)
    ctx.publisher(GlobalStatusChanged("CLASSIFYING", "Decision Engine", "PhaseClassify"))

    var updatedWafers = state.wafers
    val dispositions = scala.collection.mutable.Map.empty[String, WaferDisposition]

    state.wafers.filter { case (_, w) => !w.classification.contains("SCRAP") }.foreach {
      case (wid, info) =>
        val cdValue = info.cdValueHistory.lastOption.getOrElse(32.0)
        val disposition = DynamicFlowAssembler.classifyWafer(cdValue, spec, info.reworkCount)

        ctx.lotRef ! RecordWaferMeasured(ctx.waferUUIDs(wid), cdValue, ctx.ignoreLotReply)
        ctx.publisher(MeasurementResultEvent(wid, cdValue,
          disposition.getClass.getSimpleName.replace("Disposition", "").toUpperCase, spec.upperSpecNm))

        disposition match {
          case PassDisposition(_) =>
            updatedWafers += wid -> info.copy(classification = Some("PASS"))
            ctx.lotRef ! RecordWaferClassified(ctx.waferUUIDs(wid),"PASS", info.reworkCount, cdValue, ctx.ignoreLotReply)
            ctx.publisher(DecisionMade(wid, "PASS → Continue", None))
            dispositions += wid -> disposition
          case d: ReworkDisposition =>
            updatedWafers += wid -> info.copy(reworkCount = d.attempt, classification = Some("FAIL"))
            ctx.lotRef ! RecordWaferClassified(ctx.waferUUIDs(wid),"FAIL", d.attempt, cdValue, ctx.ignoreLotReply)
            ctx.publisher(DecisionMade(wid, s"FAIL → Split for Rework (attempt ${d.attempt}/${d.maxRetries})", None))
            dispositions += wid -> d
          case d: ScrapDisposition =>
            updatedWafers += wid -> info.copy(classification = Some("SCRAP"))
            ctx.lotRef ! RecordWaferClassified(ctx.waferUUIDs(wid),"SCRAP", info.reworkCount, cdValue, ctx.ignoreLotReply)
            ctx.publisher(DecisionMade(wid, s"SCRAP: ${d.reason}", None))
            ctx.publisher(ScrapEvent(wid, d.reason))
            dispositions += wid -> d
          case _ => dispositions += wid -> disposition
        }
    }

    val decision = DynamicFlowAssembler.decideNextStep(dispositions.toMap, step)
    ctx.publisher(OrchestratorCommand(PipelineStages.cmdId(), "DECISION-ENGINE", decision.getClass.getSimpleName,
      s"Step ${step.stepId}: $decision", PipelineStages.unresolvedIds(s.copy(wafers = updatedWafers))))

    val totalPass = updatedWafers.values.count(_.classification.contains("PASS"))
    val totalScrap = updatedWafers.values.count(_.classification.contains("SCRAP"))

    decision match {
      case AdvanceToNextStep =>
        Future.successful(s.copy(wafers = updatedWafers, passCount = totalPass, scrapCount = totalScrap,
          currentRoutingStep = s.currentRoutingStep + 1,
          areaVisitHistory = s.areaVisitHistory :+ targetAreaId,
          routingStepReentry = s.routingStepReentry + (targetAreaId -> (reentryIdx + 1)),
          ledgerSeq = s.ledgerSeq + 1))

      case RetryCurrentStep(waferIds, reason) =>
        ctx.publisher(OrchestratorCommand(PipelineStages.cmdId(), "DECISION-ENGINE", "RetryCurrentStep",
          s"Retrying step ${step.stepId}: $reason", waferIds.toSeq))
        Future.successful(s.copy(wafers = updatedWafers,
          areaVisitHistory = s.areaVisitHistory :+ targetAreaId,
          routingStepReentry = s.routingStepReentry + (targetAreaId -> (reentryIdx + 1)),
          ledgerSeq = s.ledgerSeq + 1))

      case ScrapWafersDecision(waferIds, reason) =>
        Future.successful(s.copy(wafers = updatedWafers, passCount = totalPass, scrapCount = totalScrap,
          currentRoutingStep = s.currentRoutingStep + 1,
          areaVisitHistory = s.areaVisitHistory :+ targetAreaId,
          routingStepReentry = s.routingStepReentry + (targetAreaId -> (reentryIdx + 1)),
          ledgerSeq = s.ledgerSeq + 1))

      case SplitAndRework(waferIds, reason) =>
        implicit val timeout: Timeout = 10.seconds
        val reworkWaferIds = waferIds.toSeq
        val reworkWaferUUIDs: Set[Id] = reworkWaferIds.flatMap(ctx.waferUUIDs.get).toSet
        val scrapWaferIdsInStep: Set[String] = updatedWafers.collect {
          case (wid, w) if w.classification.contains("SCRAP") => wid
        }.toSet

        // Lazy-create rework lot (idempotent — no-op if already exists)
        val createRework: Future[LotConfirmation] =
          ctx.reworkLotRef.ask[LotConfirmation](ref => CreateLot(
            s"FAB-REWORK-${ctx.sourceLotId.toString.take(8)}", Map.empty, ref,
            parentLotId = Some(ctx.sourceLotId), splitReason = Some(ReworkSplit)))

        ctx.lotRef ! RecordWafersSplitForRework(reworkWaferIds.toSet, scrapWaferIdsInStep, s.iteration + 1, ctx.ignoreLotReply)
        val sagaId = PipelineStages.cmdId()
        ctx.publisher(SagaOperationEvent(sagaId, "SplitLot", "PREPARE",
          ctx.scenario.scenarioId, s"${ctx.scenario.scenarioId}-RWK", reworkWaferIds))

        createRework.flatMap(_ =>
          ctx.sagaTx(ctx.sourceLotId, ctx.reworkLotId, reworkWaferUUIDs, reworkWaferIds.toSet)
        )(ctx.ec).map { confirmation =>
          if (confirmation.error.isEmpty) {
            ctx.publisher(SagaOperationEvent(sagaId, "SplitLot", "COMMITTED",
              ctx.scenario.scenarioId, s"${ctx.scenario.scenarioId}-RWK", reworkWaferIds))
            ctx.publisher(OrchestratorCommand(PipelineStages.cmdId(), "DECISION-ENGINE", "SplitCompleted",
              s"TCC Split committed: ${reworkWaferIds.mkString(",")} → Rework Lot", reworkWaferIds))
            val rwkSet = reworkWaferIds.toSet
            val wafersWithSubLot = updatedWafers.map { case (wid, info) =>
              if (rwkSet.contains(wid)) wid -> info.copy(subLot = Some("rwk"))
              else wid -> info
            }
            val nextState = s.copy(wafers = wafersWithSubLot, currentRoutingStep = s.currentRoutingStep + 1,
              areaVisitHistory = s.areaVisitHistory :+ targetAreaId,
              routingStepReentry = s.routingStepReentry + (targetAreaId -> (reentryIdx + 1)),
              ledgerSeq = s.ledgerSeq + 1, spawnedChildLotKey = Some("rwk"), childLotView = Map("rwk" -> ("Active", reworkWaferIds.size)))
            nextState
          } else {
            ctx.publisher(SagaOperationEvent(sagaId, "SplitLot", "FAILED", ctx.scenario.scenarioId, "", Seq.empty))
            s.copy(wafers = updatedWafers, currentRoutingStep = s.currentRoutingStep + 1,
              areaVisitHistory = s.areaVisitHistory :+ targetAreaId,
              routingStepReentry = s.routingStepReentry + (targetAreaId -> (reentryIdx + 1)),
              ledgerSeq = s.ledgerSeq + 1)
          }
        }(ctx.ec)

      case FallbackToArea(area, reason) =>
        ctx.publisher(OrchestratorCommand(PipelineStages.cmdId(), "DECISION-ENGINE", "FallbackToArea",
          s"Falling back to $area: $reason", Seq.empty))
        val fbEquipId = AreaToEquipmentId.getOrElse(area.areaId, s"${area.areaId}-01")
        val fbReentry = DynamicFlowAssembler.calculateReentryIndex(area.areaId, s.areaVisitHistory)
        for {
          s1 <- PipelineStages.transport(s, ctx, s.areaVisitHistory.lastOption.getOrElse("STOCKER"), area.areaId)
          s2 <- PipelineStages.atEquipment(s1, ctx, area.areaId, fbEquipId)
          s2b <- PipelineStages.trackIn(s2, ctx, fbEquipId)
          s3 <- PipelineStages.process(s2b, ctx, fbEquipId, step.recipeId, area.areaId)
          s3b <- PipelineStages.trackOut(s3, ctx, fbEquipId)
          s4 <- if (MeasureAreas.contains(area.areaId))
            measureAndClassify(s3b, ctx, step.copy(equipmentArea = area), routing, spec)
          else {
            val next = s3.copy(currentRoutingStep = s3.currentRoutingStep + 1,
              areaVisitHistory = s3.areaVisitHistory :+ area.areaId,
              routingStepReentry = s3.routingStepReentry + (area.areaId -> (fbReentry + 1)),
              ledgerSeq = s3.ledgerSeq + 1)
            Future.successful(next)
          }
        } yield s4

      case _ =>
        Future.successful(s.copy(wafers = updatedWafers, currentRoutingStep = s.currentRoutingStep + 1,
          areaVisitHistory = s.areaVisitHistory :+ targetAreaId, ledgerSeq = s.ledgerSeq + 1))
    }
  }

  // ============================================================================
  // Final transport + seal
  // ============================================================================

  private def transportToStocker(state: FabDemoState, ctx: FabDemoContext, routing: Por): Future[FabDemoState] = {
    val lastArea = state.areaVisitHistory.lastOption.getOrElse("MET")
    PipelineStages.transport(state, ctx, lastArea, "STOCKER")
  }

  private def sealComplete(state: FabDemoState, ctx: FabDemoContext, routing: Por): Future[FabDemoState] = {
    val s = PipelineStages.emitLedger(state, s"PhaseComplete: All ${routing.steps.size} steps done — ${routing.productId}", ctx)
    ctx.publisher(GlobalStatusChanged("COMPLETED", s"${routing.productId} completed: ${state.passCount} PASS, ${state.scrapCount} SCRAP", "PhaseComplete"))
    ctx.lotRef ! SealLot(ctx.ignoreLotReply)
    ctx.lotRef ! CompleteProcess(routing.productId, state.passCount, state.scrapCount, 0, ctx.ignoreLotReply)
    ctx.publisher(FoupStateChanged(ctx.foupId, "COMPLETED", 0, 0, "STOCKER", lotId = routing.productId))
    ctx.publisher(FoupArrivedAtPort(ctx.foupId, StockerEquipId, "STOCKER-PORT-1"))
    ctx.publisher(LotUpdated(routing.productId, ctx.scenario.lotSize, state.scrapCount,
      (1 to routing.steps.size).map(i => s"Step-$i").toList, state.passCount, 0))
    ctx.publisher(DemoCompleted(routing.productId, ctx.scenario.lotSize, state.passCount, 0, state.scrapCount))
    Future.successful(s.copy(ledgerSeq = s.ledgerSeq + 1, currentArea = "STOCKER"))
  }

  // ============================================================================
  // Helpers
  // ============================================================================

}
