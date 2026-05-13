package net.imadz.fab.chain

import net.imadz.application.aggregates.LotProtocol.{LotConfirmation, SealLot}
import net.imadz.application.aggregates.WaferProtocol.{ScrapWafer, WaferConfirmation}
import net.imadz.application.aggregates.process.FabProcessProtocol._
import net.imadz.fab.chain.FabDemoPipeline.{FabDemoContext, FabDemoState, WaferInfo}
import net.imadz.fab.events._
import net.imadz.fab.model.{EquipmentArea, ProductRouting, RoutingStep}
import net.imadz.fab.protocol.{ProcessRecipe, TransferFoup}
import net.imadz.fab.scenario.DecisionConfig

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Dynamic flow engine — executes a [[ProductRouting]] step by step,
 * delegating runtime decisions to [[DynamicFlowAssembler]].
 *
 * Contrast with [[FabScenarioPipeline]]:
 *   - FabScenarioPipeline: static List[PipelineStage] per scenario
 *   - FabFlowEngine:        while-loop over ProductRouting.steps, dynamic
 *
 * Compatible with [[FabChainExecutor]] via the same function signature:
 *   (FabDemoState, FabDemoContext) => Future[FabDemoState]
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
    "CLEAN" -> "CLEAN-01",
    "DIFF"  -> "DIFF-01",
    "LITHO" -> "LITHO-01",
    "ETCH"  -> "ETCH-01",
    "IMPL"  -> "IMPL-01",
    "DEP"   -> "DEP-01",
    "CMP"   -> "CMP-01",
    "MET"   -> "CDSEM-01",
    "DRY"   -> "DRY-01",
    "LOG"   -> "LOG-01"
  )

  val CdsemEquipId = "CDSEM-01"
  val StockerEquipId = "STOCKER-01"

  /** Steps requiring CD measurement after processing */
  val MeasureAreas: Set[String] = Set("LITHO", "ETCH", "MET")

  /** Default AMHS routes (all area pairs connect with 2s transport) */
  val DefaultRoutes: Map[(String, String), FiniteDuration] = {
    val areaIds = Seq("STOCKER") ++ EquipmentArea.all.map(_.areaId)
    (for {
      from <- areaIds
      to <- areaIds
      if from != to
    } yield (from, to) -> 2.seconds).toMap
  }

  // ============================================================================
  // Main entry point
  // ============================================================================

  def runRouting(routing: ProductRouting, spec: DecisionConfig = DefaultDecisionConfig)(
    initialState: FabDemoState, ctx: FabDemoContext
  ): Future[FabDemoState] = {
    implicit val ec: ExecutionContext = ctx.ec
    // Initialize state with routing tracking
    val init = initialState.copy(
      currentRoutingStep = initialState.currentRoutingStep,
      routingStepReentry = initialState.routingStepReentry,
      areaVisitHistory = initialState.areaVisitHistory
    )

    for {
      s1  <- loadFoup(init, ctx, routing)
      s2  <- executeSteps(s1, ctx, routing, spec)
      s3  <- transportToStocker(s2, ctx, routing)
      s4  <- sealComplete(s3, ctx, routing)
    } yield s4
  }

  // ============================================================================
  // Step loop
  // ============================================================================

  private def executeSteps(
    state: FabDemoState, ctx: FabDemoContext, routing: ProductRouting, spec: DecisionConfig
  ): Future[FabDemoState] = {
    implicit val ec: ExecutionContext = ctx.ec

    def loop(s: FabDemoState): Future[FabDemoState] = {
      if (s.currentRoutingStep >= routing.steps.length) {
        Future.successful(s)
      } else {
        val step = routing.steps(s.currentRoutingStep)
        val prevAreaId = s.areaVisitHistory.lastOption.getOrElse("STOCKER")
        val targetAreaId = step.equipmentArea.areaId
        val equipId = AreaToEquipmentId.getOrElse(targetAreaId, s"$targetAreaId-01")
        val reentryIdx = DynamicFlowAssembler.calculateReentryIndex(targetAreaId, s.areaVisitHistory)

        emitLedger(s, s"Step ${s.currentRoutingStep + 1}/${routing.steps.length}: $targetAreaId (reentry=$reentryIdx) — ${step.recipeId}", ctx)

        for {
          s1 <- transport(s, ctx, prevAreaId, targetAreaId)
          s2 <- atEquipment(s1, ctx, targetAreaId, equipId)
          s3 <- processStep(s2, ctx, equipId, step)
          s4 <- if (MeasureAreas.contains(targetAreaId)) {
            measureAndClassify(s3, ctx, step, routing, spec)
          } else {
            // Non-measurement step: all active wafers auto-PASS
            val next = s3.copy(
              currentRoutingStep = s3.currentRoutingStep + 1,
              areaVisitHistory = s3.areaVisitHistory :+ targetAreaId,
              routingStepReentry = s3.routingStepReentry + (targetAreaId -> (reentryIdx + 1)),
              ledgerSeq = s3.ledgerSeq + 1
            )
            ctx.publisher(LedgerStepAdvanced(s3.ledgerSeq, s"Step ${s3.currentRoutingStep + 1}/" +
              s"${routing.steps.length}: $targetAreaId — no measurement, auto-advance"))
            Future.successful(next)
          }
          s5 <- loop(s4)
        } yield s5
      }
    }

    loop(state)
  }

  // ============================================================================
  // Stage functions (same patterns as FabScenarioPipeline)
  // ============================================================================

  private def loadFoup(state: FabDemoState, ctx: FabDemoContext, routing: ProductRouting): Future[FabDemoState] = {
    val s = emitLedger(state, s"PhaseLoad: Load FOUP — ${routing.productId} (${routing.steps.size} steps)", ctx)
    ctx.publisher(GlobalStatusChanged("LOADING", s"Starting ${routing.productId}", "PhaseLoad"))
    ctx.processRef ! StartProcess(routing.productId, state.wafers.keySet, state.wafers.size, ctx.ignoreReply)
    ctx.processRef ! RecordFoupLoaded(ctx.foupId, StockerEquipId, ctx.ignoreReply)
    ctx.publisher(FoupStateChanged(ctx.foupId, "LOADING", activeCount(state), 0, "STOCKER",
      lotId = routing.productId))
    ctx.publisher(FoupArrivedAtPort(ctx.foupId, StockerEquipId, "STOCKER-PORT-1"))
    ctx.publisher(EquipmentStateChanged(StockerEquipId, "STOCKER", "Idle", None))
    Future.successful(s.copy(ledgerSeq = s.ledgerSeq + 1))
  }

  private def transport(state: FabDemoState, ctx: FabDemoContext, from: String, to: String): Future[FabDemoState] = {
    val routeKey = from -> to
    val routeMs = DefaultRoutes.get(routeKey).map(_.toMillis).getOrElse(2000L)
    val scaledMs = (routeMs / ctx.speedMultiplier).toLong
    val s = emitLedger(state, s"Transport: $from → $to (${scaledMs}ms)", ctx)
    ctx.publisher(GlobalStatusChanged("TRANSPORTING", s"$from → $to", "PhaseTransport"))
    ctx.processRef ! RecordTransportStarted(ctx.foupId, from, to, scaledMs, ctx.ignoreReply)
    ctx.publisher(FoupInTransit(ctx.foupId, from, to, scaledMs / 2))
    ctx.publisher(FoupStateChanged(ctx.foupId, "IN_TRANSIT", activeCount(state), 0, "AMHS",
      lotId = ctx.scenario.scenarioId))
    ctx.adapter.sendCommand("AMHS", TransferFoup(ctx.foupId, from, to)).map(_ =>
      s.copy(ledgerSeq = s.ledgerSeq + 1)
    )(ctx.ec)
  }

  private def atEquipment(state: FabDemoState, ctx: FabDemoContext, area: String, equipId: String): Future[FabDemoState] = {
    val s = emitLedger(state, s"Arrive: $equipId ($area)", ctx)
    ctx.publisher(GlobalStatusChanged("AT_EQP", s"FOUP at $area", s"PhaseAt$area"))
    ctx.processRef ! RecordTransportCompleted(ctx.foupId, equipId, ctx.ignoreReply)
    ctx.publisher(FoupArrivedAtPort(ctx.foupId, equipId, s"$equipId-PORT-1"))
    ctx.publisher(FoupStateChanged(ctx.foupId, "AT_EQUIPMENT", activeCount(state), 0, area,
      lotId = ctx.scenario.scenarioId))
    ctx.publisher(EquipmentStateChanged(equipId, area, "Idle", None))
    Future.successful(s.copy(ledgerSeq = s.ledgerSeq + 1))
  }

  private def processStep(state: FabDemoState, ctx: FabDemoContext, equipId: String, step: RoutingStep): Future[FabDemoState] = {
    val s = emitLedger(state, s"Process: ${step.recipeId} on $equipId", ctx)
    ctx.publisher(GlobalStatusChanged("PROCESSING", s"$equipId running ${step.recipeId}", "PhaseProcess"))
    val scaledMs = (step.expectedDuration.toMillis / ctx.speedMultiplier).toLong
    ctx.processRef ! RecordEquipmentJobStarted(equipId, step.recipeId, ctx.ignoreReply)
    ctx.publisher(EquipmentStateChanged(equipId, step.equipmentArea.areaId, "Busy", Some(s"job-${step.recipeId}")))
    ctx.publisher(ProcessingStarted(equipId, step.recipeId, scaledMs))
    ctx.adapter.sendCommand(equipId, ProcessRecipe(step.recipeId)).map {
      case net.imadz.fab.protocol.JobCompleted(jobId, _, _) =>
        ctx.processRef ! RecordEquipmentJobCompleted(equipId, jobId, success = true, ctx.ignoreReply)
        ctx.publisher(ProcessingCompleted(equipId, jobId, success = true, ""))
        ctx.publisher(EquipmentStateChanged(equipId, step.equipmentArea.areaId, "Idle", None))
        s.copy(ledgerSeq = s.ledgerSeq + 1)
      case _ => s.copy(ledgerSeq = s.ledgerSeq + 1)
    }(ctx.ec)
  }

  private def measureAndClassify(
    state: FabDemoState, ctx: FabDemoContext, step: RoutingStep,
    routing: ProductRouting, spec: DecisionConfig
  ): Future[FabDemoState] = {
    implicit val ec: ExecutionContext = ctx.ec
    val targetAreaId = step.equipmentArea.areaId
    val reentryIdx = state.routingStepReentry.getOrElse(targetAreaId, 0)

    val scaledMs = (5000L / ctx.speedMultiplier).toLong

    for {
      // Transport to CD-SEM
      s1 <- transport(state, ctx, targetAreaId, "MET")
      s2 <- atEquipment(s1, ctx, "MET", CdsemEquipId)
      // Measure
      s3 = emitLedger(s2, s"Measure: CD-SEM — ${step.recipeId}", ctx)
      _ = {
        ctx.publisher(GlobalStatusChanged("MEASURING", "CD measurement", "PhaseMeasure"))
        ctx.processRef ! RecordEquipmentJobStarted(CdsemEquipId, "CD-MEASURE-001", ctx.ignoreReply)
        ctx.publisher(EquipmentStateChanged(CdsemEquipId, "MET", "Busy", Some("metrology-job")))
        ctx.publisher(ProcessingStarted(CdsemEquipId, "CD-MEASURE-001", scaledMs))
      }
      result <- ctx.adapter.sendCommand(CdsemEquipId, ProcessRecipe("CD-MEASURE-001"))
      s4 <- result match {
        case net.imadz.fab.protocol.JobCompleted(jobId, _, net.imadz.fab.protocol.MetrologyResult(_, waferMeasurements)) =>
          Future.successful {
            ctx.processRef ! RecordEquipmentJobCompleted(CdsemEquipId, jobId, success = true, ctx.ignoreReply)
            ctx.publisher(ProcessingCompleted(CdsemEquipId, jobId, success = true, ""))
            ctx.publisher(EquipmentStateChanged(CdsemEquipId, "MET", "Idle", None))
            val cdValues: Map[String, Double] = waferMeasurements.map { case (wid, cd) => wid -> cd.measuredNm }
            s3.copy(ledgerSeq = s3.ledgerSeq + 1, wafers = s3.wafers.map { case (wid, info) =>
              wid -> info.copy(cdValueHistory = info.cdValueHistory ++ cdValues.get(wid).toList)
            })
          }
        case _ => Future.successful(s3.copy(ledgerSeq = s3.ledgerSeq + 1))
      }
      // Classify
      s5 <- classifyAndDecide(s4, ctx, step, routing, spec, reentryIdx, targetAreaId)
    } yield s5
  }

  private def classifyAndDecide(
    state: FabDemoState, ctx: FabDemoContext, step: RoutingStep,
    routing: ProductRouting, spec: DecisionConfig,
    reentryIdx: Int, targetAreaId: String
  )(implicit ec: ExecutionContext): Future[FabDemoState] = {
    val s = emitLedger(state, s"Classify: Decision Engine — ${step.stepId}", ctx)
    ctx.publisher(GlobalStatusChanged("CLASSIFYING", "Decision Engine", "PhaseClassify"))

    var updatedWafers = state.wafers
    val dispositions = scala.collection.mutable.Map.empty[String, WaferDisposition]

    state.wafers.filter { case (_, w) => w.classification.isEmpty || w.classification.contains("FAIL") }.foreach {
      case (wid, info) =>
        val cdValue = info.cdValueHistory.lastOption.getOrElse(32.0)
        val disposition = DynamicFlowAssembler.classifyWafer(cdValue, spec, info.reworkCount)

        ctx.processRef ! RecordWaferMeasured(wid, cdValue, ctx.ignoreReply)
        ctx.publisher(MeasurementResultEvent(wid, cdValue,
          disposition.getClass.getSimpleName.replace("Disposition", "").toUpperCase, spec.upperSpecNm))

        disposition match {
          case PassDisposition(_) =>
            updatedWafers += wid -> info.copy(classification = Some("PASS"))
            ctx.processRef ! RecordWaferClassified(wid, "PASS", info.reworkCount, cdValue, ctx.ignoreReply)
            ctx.publisher(DecisionMade(wid, "PASS → Continue", None))
            dispositions += wid -> disposition
          case d: ReworkDisposition =>
            updatedWafers += wid -> info.copy(reworkCount = d.attempt, classification = Some("FAIL"))
            ctx.processRef ! RecordWaferClassified(wid, "FAIL", d.attempt, cdValue, ctx.ignoreReply)
            ctx.publisher(DecisionMade(wid, s"FAIL → Rework (attempt ${d.attempt}/${d.maxRetries})", None))
            dispositions += wid -> d
          case d: ScrapDisposition =>
            updatedWafers += wid -> info.copy(classification = Some("SCRAP"))
            ctx.processRef ! RecordWaferClassified(wid, "SCRAP", info.reworkCount, cdValue, ctx.ignoreReply)
            ctx.publisher(DecisionMade(wid, s"SCRAP: ${d.reason}", None))
            ctx.publisher(ScrapEvent(wid, d.reason))
            dispositions += wid -> d
            // Send scrap command to wafer aggregate
            ctx.waferRefs.get(wid).foreach { ref =>
              ref ! ScrapWafer(d.reason, ctx.ignoreWaferReply)
            }
          case _ => dispositions += wid -> disposition
        }
    }

    val decision = DynamicFlowAssembler.decideNextStep(dispositions.toMap, step)
    ctx.publisher(OrchestratorCommand(cmdId(), "DECISION-ENGINE", decision.getClass.getSimpleName,
      s"Step ${step.stepId}: $decision", unresolvedIds(updatedWafers)))

    val totalPass = updatedWafers.values.count(_.classification.contains("PASS"))
    val totalScrap = updatedWafers.values.count(_.classification.contains("SCRAP"))

    decision match {
      case AdvanceToNextStep =>
        val next = s.copy(wafers = updatedWafers, passCount = totalPass, scrapCount = totalScrap,
          currentRoutingStep = s.currentRoutingStep + 1,
          areaVisitHistory = s.areaVisitHistory :+ targetAreaId,
          routingStepReentry = s.routingStepReentry + (targetAreaId -> (reentryIdx + 1)),
          ledgerSeq = s.ledgerSeq + 1)
        Future.successful(next)

      case RetryCurrentStep(waferIds, reason) =>
        val next = s.copy(wafers = updatedWafers,
          areaVisitHistory = s.areaVisitHistory :+ targetAreaId,
          routingStepReentry = s.routingStepReentry + (targetAreaId -> (reentryIdx + 1)),
          ledgerSeq = s.ledgerSeq + 1)
        ctx.publisher(OrchestratorCommand(cmdId(), "DECISION-ENGINE", "RetryCurrentStep",
          s"Retrying step ${step.stepId}: $reason (wafers: ${waferIds.mkString(",")})", waferIds.toSeq))
        Future.successful(next) // currentRoutingStep stays same → retry

      case ScrapWafersDecision(waferIds, reason) =>
        val next = s.copy(wafers = updatedWafers, passCount = totalPass, scrapCount = totalScrap,
          currentRoutingStep = s.currentRoutingStep + 1,
          areaVisitHistory = s.areaVisitHistory :+ targetAreaId,
          routingStepReentry = s.routingStepReentry + (targetAreaId -> (reentryIdx + 1)),
          ledgerSeq = s.ledgerSeq + 1)
        Future.successful(next)

      case SplitAndRework(waferIds, reason) =>
        ctx.publisher(OrchestratorCommand(cmdId(), "DECISION-ENGINE", "SplitAndRework",
          s"Split ${waferIds.size} wafers for offline rework: $reason", waferIds.toSeq))
        val next = s.copy(wafers = updatedWafers,
          currentRoutingStep = s.currentRoutingStep + 1,
          areaVisitHistory = s.areaVisitHistory :+ targetAreaId,
          routingStepReentry = s.routingStepReentry + (targetAreaId -> (reentryIdx + 1)),
          ledgerSeq = s.ledgerSeq + 1)
        Future.successful(next)

      case FallbackToArea(area, reason) =>
        ctx.publisher(OrchestratorCommand(cmdId(), "DECISION-ENGINE", "FallbackToArea",
          s"Falling back to $area: $reason", Seq.empty))
        // Re-execute current step with fallback area
        val fbEquipId = AreaToEquipmentId.getOrElse(area.areaId, s"${area.areaId}-01")
        val fbReentry = DynamicFlowAssembler.calculateReentryIndex(area.areaId, s.areaVisitHistory)
        for {
          s1 <- transport(s, ctx, s.areaVisitHistory.lastOption.getOrElse("STOCKER"), area.areaId)
          s2 <- atEquipment(s1, ctx, area.areaId, fbEquipId)
          s3 <- processStep(s2, ctx, fbEquipId, step.copy(equipmentArea = area))
          s4 <- if (MeasureAreas.contains(area.areaId))
            measureAndClassify(s3, ctx, step.copy(equipmentArea = area), routing, spec)
          else {
            val next = s3.copy(
              currentRoutingStep = s3.currentRoutingStep + 1,
              areaVisitHistory = s3.areaVisitHistory :+ area.areaId,
              routingStepReentry = s3.routingStepReentry + (area.areaId -> (fbReentry + 1)),
              ledgerSeq = s3.ledgerSeq + 1)
            Future.successful(next)
          }
        } yield s4

      case _ =>
        val next = s.copy(wafers = updatedWafers,
          currentRoutingStep = s.currentRoutingStep + 1,
          areaVisitHistory = s.areaVisitHistory :+ targetAreaId,
          ledgerSeq = s.ledgerSeq + 1)
        Future.successful(next)
    }
  }

  // ============================================================================
  // Final transport + seal
  // ============================================================================

  private def transportToStocker(state: FabDemoState, ctx: FabDemoContext, routing: ProductRouting): Future[FabDemoState] = {
    val lastArea = state.areaVisitHistory.lastOption.getOrElse("MET")
    transport(state, ctx, lastArea, "STOCKER")
  }

  private def sealComplete(state: FabDemoState, ctx: FabDemoContext, routing: ProductRouting): Future[FabDemoState] = {
    val s = emitLedger(state, s"PhaseComplete: All ${routing.steps.size} steps done — ${routing.productId}", ctx)
    ctx.publisher(GlobalStatusChanged("COMPLETED", s"${routing.productId} completed: ${state.passCount} PASS, ${state.scrapCount} SCRAP", "PhaseComplete"))
    ctx.lotRef ! SealLot(ctx.ignoreLotReply)
    ctx.processRef ! CompleteProcess(routing.productId, state.passCount, state.scrapCount, 0, ctx.ignoreReply)
    ctx.publisher(FoupStateChanged(ctx.foupId, "COMPLETED", 0, 0, "STOCKER", lotId = routing.productId))
    ctx.publisher(FoupArrivedAtPort(ctx.foupId, StockerEquipId, "STOCKER-PORT-1"))
    ctx.publisher(LotUpdated(routing.productId, ctx.scenario.lotSize, state.scrapCount,
      (1 to routing.steps.size).map(i => s"Step-$i").toList, state.passCount, 0))
    ctx.publisher(DemoCompleted(routing.productId, ctx.scenario.lotSize, state.passCount, 0, state.scrapCount))
    Future.successful(s.copy(ledgerSeq = s.ledgerSeq + 1))
  }

  // ============================================================================
  // Helpers
  // ============================================================================

  private def activeCount(state: FabDemoState): Int =
    state.wafers.values.count(w => w.classification.isEmpty || w.classification.contains("FAIL"))

  private def unresolvedIds(wafers: Map[String, WaferInfo]): Seq[String] =
    wafers.values.filter(w => w.classification.isEmpty || w.classification.contains("FAIL")).map(_.waferId).toSeq

  private def cmdId(): String = UUID.randomUUID().toString.take(8)

  private def emitLedger(state: FabDemoState, name: String, ctx: FabDemoContext): FabDemoState = {
    ctx.publisher(LedgerStepAdvanced(state.ledgerSeq, name))
    state
  }
}
