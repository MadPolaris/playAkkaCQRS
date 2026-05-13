package net.imadz.fab.chain

import net.imadz.application.aggregates.LotProtocol.{LotCommand, LotConfirmation, SealLot}
import net.imadz.application.aggregates.WaferProtocol.{ScrapWafer, WaferCommand, WaferConfirmation}
import net.imadz.application.aggregates.process.FabProcessProtocol.FabProcessCommand
import net.imadz.application.aggregates.process.FabProcessProtocol._
import net.imadz.application.services.transactor.FabSagaProtocol.FabSagaConfirmation
import net.imadz.common.CommonTypes.Id
import net.imadz.fab.events._
import net.imadz.fab.protocol._
import net.imadz.fab.scenario.{DecisionConfig, FabSimulationScenario}
import net.imadz.fab.simulation.CdSemConfig

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

/**
 * Fab Demo Pipeline — M2.5+ chain-aligned stage functions.
 *
 * Each stage is a function `(FabDemoState, FabDemoContext) => Future[FabDemoState]`.
 * Equipment interaction stages return [[Future]] via [[ActorEquipmentAdapter.sendCommand]].
 * Saga stages delegate to `sagaTx` which returns [[Future[FabSagaConfirmation]]].
 *
 * All [[FabSimulationEvent]] publishing happens synchronously within each stage,
 * preserving the existing WebSocket event contract.
 */
object FabDemoPipeline {

  case class WaferInfo(
    waferId: String,
    reworkCount: Int = 0,
    cdValueHistory: List[Double] = Nil,
    classification: Option[String] = None,
    subLot: Option[String] = None
  )

  case class FabDemoState(
    wafers: Map[String, WaferInfo],
    passCount: Int = 0,
    scrapCount: Int = 0,
    iteration: Int = 0,
    ledgerSeq: Int = 0,
    pilotPassed: Boolean = false,
    reviewApproved: Boolean = false,
    spawnedChildLotKey: Option[String] = None,
    /** M3.5: current index into ProductRouting.steps (0-based) */
    currentRoutingStep: Int = 0,
    /** M3.5: reentry count per equipment area visited so far */
    routingStepReentry: Map[String, Int] = Map.empty,
    /** M3.5: ordered list of area IDs visited (for reentry calculation) */
    areaVisitHistory: List[String] = Nil
  )

  case class FabDemoContext(
    scenario: FabSimulationScenario,
    foupId: String,
    processRef: akka.cluster.sharding.typed.scaladsl.EntityRef[FabProcessCommand],
    lotRef: akka.cluster.sharding.typed.scaladsl.EntityRef[LotCommand],
    reworkLotRef: akka.cluster.sharding.typed.scaladsl.EntityRef[LotCommand],
    waferRefs: Map[String, akka.cluster.sharding.typed.scaladsl.EntityRef[WaferCommand]],
    waferUUIDs: Map[String, Id],
    sourceLotId: Id,
    reworkLotId: Id,
    adapter: ActorEquipmentAdapter,
    publisher: FabSimulationEvent => Unit,
    ignoreReply: akka.actor.typed.ActorRef[ProcessConfirmation],
    ignoreLotReply: akka.actor.typed.ActorRef[LotConfirmation],
    ignoreWaferReply: akka.actor.typed.ActorRef[WaferConfirmation],
    sagaTx: (Id, Id, Set[Id]) => Future[FabSagaConfirmation],
    speedMultiplier: Double,
    childLotRefs: Map[String, akka.cluster.sharding.typed.scaladsl.EntityRef[LotCommand]] = Map.empty,
    childLotIds: Map[String, Id] = Map.empty
  )(implicit val ec: ExecutionContext)

  // ====================================================================
  // Pipeline runner
  // ====================================================================

  def runPipeline(initialState: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    implicit val ec: ExecutionContext = ctx.ec
    for {
      s1  <- loadFoup(initialState, ctx)
      s2  <- transport(s1, ctx, "STOCKER", "LITHO")
      s3  <- atEquipment(s2, ctx, "LITHO", ctx.scenario.litho.equipmentId, "LITHO-PORT-1")
      s4  <- lithoProcess(s3, ctx)
      s5  <- transport(s4, ctx, "LITHO", "CDSEM")
      s6  <- atEquipment(s5, ctx, "CDSEM", ctx.scenario.cdSem.equipmentId, "CDSEM-PORT-1")
      s7  <- cdSemMeasure(s6, ctx)
      s8  <- classify(s7, ctx)
      s9  <- maybeRework(s8, ctx)
      s10 <- transport(s9, ctx, "CDSEM", "STOCKER")
      s11 <- sealComplete(s10, ctx)
    } yield s11
  }

  // ====================================================================
  // Stage 1: LoadFoup
  // ====================================================================
  private def loadFoup(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    emitLedger(state, "PhaseLoad: Load FOUP from Stocker", ctx)
    ctx.publisher(GlobalStatusChanged("LOADING", "Loading FOUP from Stocker", "PhaseLoad"))
    ctx.processRef ! StartProcess(ctx.scenario.scenarioId, state.wafers.keySet, state.wafers.size, ctx.ignoreReply)
    ctx.processRef ! RecordFoupLoaded(ctx.foupId, ctx.scenario.stocker.equipmentId, ctx.ignoreReply)
    ctx.publisher(OrchestratorCommand(cmdId(), "STOCKER-01", "LoadFoup",
      s"Load ${ctx.foupId} with ${state.wafers.size} wafers", ctx.scenario.waferIds))
    ctx.publisher(FoupStateChanged(ctx.foupId, "LOADING", activeCount(state), 0, "STOCKER",
      lotId = ctx.scenario.scenarioId))
    ctx.publisher(FoupArrivedAtPort(ctx.foupId, ctx.scenario.stocker.equipmentId, "STOCKER-PORT-1"))
    ctx.publisher(buildAggregateStateFromWafers(state.wafers, ctx, reworkActive = false,
      totalPass = state.passCount, totalScrap = state.scrapCount))
    Future.successful(state.copy(ledgerSeq = state.ledgerSeq + 1))
  }

  // ====================================================================
  // Stage 2/5/10: AMHS Transport
  // ====================================================================
  private def transport(state: FabDemoState, ctx: FabDemoContext, from: String, to: String): Future[FabDemoState] = {
    val phaseName = s"PhaseTransport: $from → $to"
    val s = emitLedger(state, phaseName, ctx)
    ctx.publisher(GlobalStatusChanged("TRANSPORTING", s"$from → $to", "PhaseTransport"))

    val routeKey = from -> to
    val scaledMs = scale(ctx.scenario.amhs.routes(routeKey), ctx.speedMultiplier).toMillis
    ctx.processRef ! RecordTransportStarted(ctx.foupId, from, to, scaledMs, ctx.ignoreReply)
    ctx.publisher(OrchestratorCommand(cmdId(), "AMHS", "TransferFoup",
      s"Transport ${ctx.foupId}: $from → $to (${scaledMs}ms)", unresolvedIds(state)))
    ctx.publisher(FoupStateChanged(ctx.foupId, "IN_TRANSIT", activeCount(state), reworkCount(state), "AMHS",
      lotId = ctx.scenario.scenarioId))
    ctx.publisher(FoupInTransit(ctx.foupId, from, to, scaledMs / 2))

    ctx.adapter.sendCommand("AMHS", TransferFoup(ctx.foupId, from, to)).map { _ =>
      s.copy(ledgerSeq = s.ledgerSeq + 1)
    }(ctx.ec)
  }

  // ====================================================================
  // Stage 3/6: At Equipment (arrival)
  // ====================================================================
  private def atEquipment(state: FabDemoState, ctx: FabDemoContext, area: String, equipId: String, portId: String): Future[FabDemoState] = {
    val s = emitLedger(state, s"PhaseAt$area: FOUP arrives at $area", ctx)
    ctx.publisher(GlobalStatusChanged("AT_EQP", s"FOUP at $area", s"PhaseAt$area"))
    ctx.processRef ! RecordTransportCompleted(ctx.foupId, equipId, ctx.ignoreReply)
    ctx.publisher(FoupArrivedAtPort(ctx.foupId, equipId, portId))
    ctx.publisher(FoupStateChanged(ctx.foupId, "AT_EQUIPMENT", activeCount(state), reworkCount(state), area,
      lotId = ctx.scenario.scenarioId))
    ctx.publisher(EquipmentStateChanged(equipId, if (area == "LITHO") "LITHO" else "METROLOGY", "Idle", None))
    Future.successful(s.copy(ledgerSeq = s.ledgerSeq + 1))
  }

  // ====================================================================
  // Stage 4: Litho Process
  // ====================================================================
  private def lithoProcess(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    val s = emitLedger(state, "PhaseLithoProcess: Run Litho recipe", ctx)
    ctx.publisher(GlobalStatusChanged("PROCESSING", "Lithography processing", "PhaseLithoProcess"))

    val litho = ctx.scenario.litho
    val scaledMs = scale(litho.processingTime, ctx.speedMultiplier).toMillis
    val recipeId = if (state.iteration == 0) "LITHO-28-001" else ctx.scenario.decision.reworkRecipeId

    ctx.processRef ! RecordEquipmentJobStarted(litho.equipmentId, recipeId, ctx.ignoreReply)
    ctx.publisher(OrchestratorCommand(cmdId(), litho.equipmentId, "ProcessRecipe",
      s"Run $recipeId on ${activeCount(state)} wafers (${scaledMs}ms)", unresolvedIds(state)))
    ctx.publisher(EquipmentStateChanged(litho.equipmentId, "LITHO", "Busy", Some(s"litho-job-${state.iteration}")))
    ctx.publisher(ProcessingStarted(litho.equipmentId, recipeId, scaledMs))

    ctx.adapter.sendCommand(litho.equipmentId, ProcessRecipe(recipeId)).map {
      case JobCompleted(jobId, _, _) =>
        ctx.processRef ! RecordEquipmentJobCompleted(litho.equipmentId, jobId, success = true, ctx.ignoreReply)
        ctx.publisher(ProcessingCompleted(litho.equipmentId, jobId, success = true, ""))
        ctx.publisher(EquipmentStateChanged(litho.equipmentId, "LITHO", "Idle", None))
        s.copy(ledgerSeq = s.ledgerSeq + 1)
      case _ => s.copy(ledgerSeq = s.ledgerSeq + 1)
    }(ctx.ec)
  }

  // ====================================================================
  // Stage 7: CD-SEM Measure
  // ====================================================================
  private def cdSemMeasure(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    val s = emitLedger(state, "PhaseCdSemMeasure: Measure CD on wafers", ctx)
    ctx.publisher(GlobalStatusChanged("MEASURING", "CD measurement on wafers", "PhaseCdSemMeasure"))

    val cdSem = ctx.scenario.cdSem
    val scaledMs = scale(cdSem.processingTime, ctx.speedMultiplier).toMillis
    ctx.processRef ! RecordEquipmentJobStarted(cdSem.equipmentId, "CD-MEASURE-001", ctx.ignoreReply)
    ctx.publisher(OrchestratorCommand(cmdId(), cdSem.equipmentId, "ProcessRecipe",
      s"Measure CD on ${activeCount(state)} wafers (${scaledMs}ms)", unresolvedIds(state)))
    ctx.publisher(EquipmentStateChanged(cdSem.equipmentId, "METROLOGY", "Busy", Some(s"metrology-job-${state.iteration}")))
    ctx.publisher(ProcessingStarted(cdSem.equipmentId, "CD-MEASURE-001", scaledMs))

    ctx.adapter.sendCommand(cdSem.equipmentId, ProcessRecipe("CD-MEASURE-001")).map {
      case JobCompleted(jobId, _, MetrologyResult(_, waferMeasurements)) =>
        ctx.processRef ! RecordEquipmentJobCompleted(cdSem.equipmentId, jobId, success = true, ctx.ignoreReply)
        ctx.publisher(ProcessingCompleted(cdSem.equipmentId, jobId, success = true, ""))
        ctx.publisher(EquipmentStateChanged(cdSem.equipmentId, "METROLOGY", "Idle", None))
        // Pass CD values to classify stage via wafers
        val cdValues: Map[String, Double] = waferMeasurements.map { case (wid, cd) => wid -> cd.measuredNm }
        s.copy(ledgerSeq = s.ledgerSeq + 1, wafers = s.wafers.map { case (wid, info) =>
          wid -> info.copy(cdValueHistory = info.cdValueHistory ++ cdValues.get(wid).toList)
        })
      case _ => s.copy(ledgerSeq = s.ledgerSeq + 1)
    }(ctx.ec)
  }

  // ====================================================================
  // Stage 8: Classify
  // ====================================================================
  private def classify(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    val s = emitLedger(state, "PhaseClassify: Decision Engine classifies wafers", ctx)
    ctx.publisher(GlobalStatusChanged("CLASSIFYING", "Decision Engine classifying", "PhaseClassify"))

    val decisionConfig = ctx.scenario.decision
    val maxRework = decisionConfig.maxReworkCount
    var updatedWafers = state.wafers
    var passWafers = Seq.empty[String]
    var reworkWafers = Seq.empty[String]
    var scrapWafers = Seq.empty[String]

    // For each unresolved wafer, classify based on CD value
    state.wafers.filter { case (_, w) => w.classification.isEmpty || w.classification.contains("FAIL") }.foreach { case (wid, info) =>
      val cdValue = info.cdValueHistory.lastOption.getOrElse(generateCdValue(ctx.scenario.cdSemDetail))
      if (info.reworkCount > 0) {
        updatedWafers += wid -> info.copy(classification = Some("PASS"))
        passWafers :+= wid
        ctx.processRef ! RecordWaferMeasured(wid, cdValue, ctx.ignoreReply)
        ctx.processRef ! RecordWaferClassified(wid, "PASS", info.reworkCount, cdValue, ctx.ignoreReply)
        ctx.publisher(MeasurementResultEvent(wid, cdValue, "PASS", decisionConfig.upperSpecNm))
        ctx.publisher(DecisionMade(wid, "Rework → PASS", None))
      } else {
        val cls = classifyCd(cdValue, decisionConfig)
        ctx.processRef ! RecordWaferMeasured(wid, cdValue, ctx.ignoreReply)
        ctx.publisher(MeasurementResultEvent(wid, cdValue, cls, decisionConfig.upperSpecNm))
        cls match {
          case "PASS" =>
            updatedWafers += wid -> info.copy(classification = Some("PASS"))
            passWafers :+= wid
            ctx.processRef ! RecordWaferClassified(wid, "PASS", 0, cdValue, ctx.ignoreReply)
            ctx.publisher(DecisionMade(wid, "PASS → Continue", None))
          case "BORDERLINE" =>
            if (info.reworkCount == 0) {
              updatedWafers += wid -> info.copy(classification = Some("PASS"))
              passWafers :+= wid
              ctx.processRef ! RecordWaferClassified(wid, "PASS", 0, cdValue, ctx.ignoreReply)
              ctx.publisher(DecisionMade(wid, "BORDERLINE → Conditional Pass", None))
            } else {
              val nc = info.reworkCount + 1
              if (nc >= maxRework) {
                updatedWafers += wid -> info.copy(reworkCount = nc, classification = Some("SCRAP"))
                scrapWafers :+= wid
                ctx.processRef ! RecordWaferClassified(wid, "SCRAP", nc, cdValue, ctx.ignoreReply)
                ctx.publisher(DecisionMade(wid, s"BORDERLINE → Max Rework($nc) → SCRAP", None))
                ctx.publisher(ScrapEvent(wid, s"Max rework($nc) exceeded"))
              } else {
                updatedWafers += wid -> info.copy(reworkCount = nc, classification = Some("FAIL"))
                reworkWafers :+= wid
                ctx.processRef ! RecordWaferClassified(wid, "FAIL", nc, cdValue, ctx.ignoreReply)
                ctx.publisher(DecisionMade(wid, s"BORDERLINE → Rework (attempt $nc/$maxRework)", None))
              }
            }
          case "FAIL" =>
            val nc = info.reworkCount + 1
            if (nc >= maxRework) {
              updatedWafers += wid -> info.copy(reworkCount = nc, classification = Some("SCRAP"))
              scrapWafers :+= wid
              ctx.processRef ! RecordWaferClassified(wid, "SCRAP", nc, cdValue, ctx.ignoreReply)
              ctx.publisher(DecisionMade(wid, s"FAIL → Max Rework($nc) → SCRAP", None))
              ctx.publisher(ScrapEvent(wid, s"Max rework($nc) exceeded"))
            } else {
              updatedWafers += wid -> info.copy(reworkCount = nc, classification = Some("FAIL"))
              reworkWafers :+= wid
              ctx.processRef ! RecordWaferClassified(wid, "FAIL", nc, cdValue, ctx.ignoreReply)
              ctx.publisher(DecisionMade(wid, s"FAIL → Rework (attempt $nc/$maxRework)", None))
            }
          case "SCRAP" =>
            updatedWafers += wid -> info.copy(classification = Some("SCRAP"))
            scrapWafers :+= wid
            ctx.processRef ! RecordWaferClassified(wid, "SCRAP", 0, cdValue, ctx.ignoreReply)
            ctx.publisher(DecisionMade(wid, "SCRAP → Terminate", None))
            ctx.publisher(ScrapEvent(wid, s"CD=$cdValue nm → SCRAP"))
        }
      }
    }

    // Send ScrapWafer commands to real Wafer aggregates
    scrapWafers.foreach { wid =>
      ctx.waferRefs.get(wid).foreach { ref =>
        ref ! ScrapWafer("CD measurement out of spec", ctx.ignoreWaferReply)
      }
    }

    val totalPass = updatedWafers.values.count(_.classification.contains("PASS"))
    val totalScrap = updatedWafers.values.count(_.classification.contains("SCRAP"))
    val totalRework = updatedWafers.values.count(w => w.classification.contains("FAIL") && w.reworkCount > 0)

    ctx.publisher(LotUpdated(ctx.scenario.scenarioId, ctx.scenario.lotSize, totalScrap,
      (1 to state.iteration + 1).map(i => s"Pass-$i").toList, totalPass, totalRework))
    ctx.publisher(buildAggregateStateFromWafers(updatedWafers, ctx, reworkActive = reworkWafers.nonEmpty,
      totalPass = totalPass, totalScrap = totalScrap))

    if (reworkWafers.nonEmpty) {
      ctx.processRef ! RecordWafersSplitForRework(reworkWafers.toSet, scrapWafers.toSet, state.iteration, ctx.ignoreReply)
      ctx.publisher(OrchestratorCommand(cmdId(), "DECISION-ENGINE", "SplitLot",
        s"Split ${reworkWafers.size} wafers for rework: ${reworkWafers.mkString(",")}", reworkWafers))
    }

    Future.successful(s.copy(
      wafers = updatedWafers,
      passCount = totalPass,
      scrapCount = totalScrap,
      ledgerSeq = s.ledgerSeq + 1
    ))
  }

  // ====================================================================
  // Rework sub-chain
  // ====================================================================
  private def maybeRework(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    val hasRework = state.wafers.values.exists(w => w.classification.contains("FAIL") && w.reworkCount > 0)
    if (!hasRework) {
      ctx.publisher(OrchestratorCommand(cmdId(), "DECISION-ENGINE", "CompleteLot",
        s"All wafers resolved: ${state.passCount} PASS, ${state.scrapCount} SCRAP", Seq.empty))
      Future.successful(state)
    } else {
      val newIter = state.iteration + 1
      val reworkState = state.copy(iteration = newIter)
      implicit val ec: ExecutionContext = ctx.ec
      for {
        s1 <- sagaSplit(reworkState, ctx)
        s2 <- transport(s1, ctx, "CDSEM", "LITHO")
        s3 <- atEquipment(s2, ctx, "LITHO", ctx.scenario.litho.equipmentId, "LITHO-PORT-1")
        s4 <- lithoProcess(s3, ctx)
        s5 <- transport(s4, ctx, "LITHO", "CDSEM")
        s6 <- atEquipment(s5, ctx, "CDSEM", ctx.scenario.cdSem.equipmentId, "CDSEM-PORT-1")
        s7 <- cdSemMeasure(s6, ctx)
        s8 <- classify(s7, ctx)
        s9 <- sagaMerge(s8, ctx)
      } yield s9
    }
  }

  // ====================================================================
  // Saga TCC stages (delegate to infra.saga)
  // ====================================================================
  private def sagaSplit(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    val s = emitLedger(state, "PhaseSplit: Saga SplitLot (TCC)", ctx)
    ctx.publisher(GlobalStatusChanged("SPLITTING", "Saga TCC split — rework wafers", "PhaseSplit"))

    val reworkWaferIds = state.wafers.filter { case (_, w) => w.classification.contains("FAIL") }.keys.toSeq
    val reworkWaferUUIDs: Set[Id] = reworkWaferIds.flatMap(ctx.waferUUIDs.get).toSet

    val sagaId = s"SAGA-SPLIT-${state.iteration}"
    ctx.publisher(SagaOperationEvent(sagaId, "SplitLot", "PREPARE",
      ctx.scenario.scenarioId, s"${ctx.scenario.scenarioId}-RWK", reworkWaferIds))
    ctx.publisher(FoupStateChanged(ctx.foupId, "SPLITTING", activeCount(state), reworkWaferIds.size, "CDSEM",
      lotId = ctx.scenario.scenarioId, reworkLotId = s"${ctx.scenario.scenarioId}-RWK"))

    ctx.sagaTx(ctx.sourceLotId, ctx.reworkLotId, reworkWaferUUIDs).map { confirmation =>
      if (confirmation.error.isEmpty) {
        ctx.publisher(SagaOperationEvent(sagaId, "SplitLot", "COMMITTED",
          ctx.scenario.scenarioId, s"${ctx.scenario.scenarioId}-RWK", reworkWaferIds))
        ctx.publisher(OrchestratorCommand(cmdId(), "SAGA-TCC", "SplitCompleted",
          s"TCC Split committed: ${reworkWaferIds.mkString(",")} → Rework Lot", reworkWaferIds))
        ctx.publisher(buildAggregateStateFromWafers(state.wafers, ctx, reworkActive = true,
          totalPass = state.passCount, totalScrap = state.scrapCount))
        s.copy(ledgerSeq = s.ledgerSeq + 1)
      } else {
        ctx.publisher(SagaOperationEvent(sagaId, "SplitLot", "FAILED", ctx.scenario.scenarioId, "", Seq.empty))
        s.copy(ledgerSeq = s.ledgerSeq + 1)
      }
    }(ctx.ec)
  }

  private def sagaMerge(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    val s = emitLedger(state, "PhaseMerge: Saga MergeLot (TCC)", ctx)
    ctx.publisher(GlobalStatusChanged("MERGING", "Saga TCC merge — wafers → source lot", "PhaseMerge"))

    val reworkWaferIds = state.wafers.filter { case (_, w) => w.classification.contains("FAIL") }.keys.toSeq
    val mergeWaferUUIDs: Set[Id] = reworkWaferIds.flatMap(ctx.waferUUIDs.get).toSet

    val sagaId = s"SAGA-MERGE-${state.iteration}"
    ctx.publisher(SagaOperationEvent(sagaId, "MergeLot", "PREPARE",
      s"${ctx.scenario.scenarioId}-RWK", ctx.scenario.scenarioId, reworkWaferIds))
    ctx.publisher(FoupStateChanged(ctx.foupId, "MERGING", activeCount(state), reworkWaferIds.size, "CDSEM",
      lotId = ctx.scenario.scenarioId, reworkLotId = s"${ctx.scenario.scenarioId}-RWK"))

    ctx.sagaTx(ctx.reworkLotId, ctx.sourceLotId, mergeWaferUUIDs).map { confirmation =>
      if (confirmation.error.isEmpty) {
        ctx.publisher(SagaOperationEvent(sagaId, "MergeLot", "COMMITTED",
          s"${ctx.scenario.scenarioId}-RWK", ctx.scenario.scenarioId, reworkWaferIds))
        ctx.publisher(OrchestratorCommand(cmdId(), "SAGA-TCC", "MergeCompleted",
          s"TCC Merge: ${reworkWaferIds.mkString(",")} → Source Lot", reworkWaferIds))
        // Mark reworked wafers as PASS
        val mergedWafers = state.wafers.map { case (wid, info) =>
          if (info.classification.contains("FAIL")) wid -> info.copy(classification = Some("PASS"))
          else wid -> info
        }
        s.copy(wafers = mergedWafers, ledgerSeq = s.ledgerSeq + 1)
      } else {
        ctx.publisher(SagaOperationEvent(sagaId, "MergeLot", "FAILED", ctx.scenario.scenarioId, "", Seq.empty))
        s.copy(ledgerSeq = s.ledgerSeq + 1)
      }
    }(ctx.ec)
  }

  // ====================================================================
  // Final Stage: Seal + Complete
  // ====================================================================
  private def sealComplete(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    val s = emitLedger(state, "PhaseComplete: Demo finished, Lot sealed", ctx)
    ctx.publisher(GlobalStatusChanged("COMPLETED", "Demo completed", "PhaseComplete"))

    ctx.lotRef ! SealLot(ctx.ignoreLotReply)
    val totalRework = state.wafers.values.count(_.reworkCount > 0)
    ctx.processRef ! CompleteProcess(ctx.scenario.scenarioId, state.passCount, state.scrapCount, totalRework, ctx.ignoreReply)

    ctx.publisher(FoupStateChanged(ctx.foupId, "COMPLETED", 0, 0, "STOCKER", lotId = ctx.scenario.scenarioId))
    ctx.publisher(FoupArrivedAtPort(ctx.foupId, ctx.scenario.stocker.equipmentId, "STOCKER-PORT-1"))
    ctx.publisher(LotUpdated(ctx.scenario.scenarioId, ctx.scenario.lotSize, state.scrapCount,
      (1 to state.iteration + 1).map(i => s"Completed-$i").toList, state.passCount, totalRework))
    ctx.publisher(buildAggregateStateFromWafers(state.wafers, ctx, reworkActive = false,
      totalPass = state.passCount, totalScrap = state.scrapCount))
    ctx.publisher(DemoCompleted(ctx.scenario.scenarioId, ctx.scenario.lotSize, state.passCount, totalRework, state.scrapCount))

    Future.successful(s.copy(ledgerSeq = s.ledgerSeq + 1))
  }

  // ====================================================================
  // Helpers
  // ====================================================================
  private def activeCount(state: FabDemoState): Int =
    state.wafers.values.count(w => w.classification.isEmpty || w.classification.contains("FAIL"))

  private def reworkCount(state: FabDemoState): Int =
    state.wafers.values.count(w => w.classification.contains("FAIL") && w.reworkCount > 0)

  private def unresolvedIds(state: FabDemoState): Seq[String] =
    state.wafers.values.filter(w => w.classification.isEmpty || w.classification.contains("FAIL")).map(_.waferId).toSeq

  private val rng = new scala.util.Random()
  private def cmdId(): String = UUID.randomUUID().toString.take(8)

  import scala.concurrent.duration._

  private def scale(d: FiniteDuration, multiplier: Double): FiniteDuration =
    if (multiplier > 0) (d.toMillis / multiplier).toLong.millis else d

  private def generateCdValue(config: CdSemConfig): Double = {
    val roll = rng.nextDouble()
    val rate = config.passRate + config.borderlineRate + config.failRate + config.scrapRate
    val passEnd = config.passRate / rate
    val bdEnd = passEnd + config.borderlineRate / rate
    val failEnd = bdEnd + config.failRate / rate
    if (roll < passEnd) config.targetCdNm + rng.nextGaussian() * config.spreadNm
    else if (roll < bdEnd) config.targetCdNm + config.borderlineOffsetNm + rng.nextGaussian() * config.spreadNm * 0.5
    else if (roll < failEnd) config.targetCdNm + config.failOffsetNm + rng.nextGaussian() * config.spreadNm * 0.7
    else config.targetCdNm * config.scrapFactor + rng.nextGaussian() * config.spreadNm
  }

  private def classifyCd(cdValue: Double, config: DecisionConfig): String = {
    if (cdValue >= config.lowerSpecNm && cdValue <= config.upperSpecNm) "PASS"
    else if (cdValue > config.upperSpecNm && cdValue <= config.upperSpecNm + config.borderlineWindowNm) "BORDERLINE"
    else if (cdValue > config.upperSpecNm + 8.0) "SCRAP"
    else "FAIL"
  }

  private def emitLedger(state: FabDemoState, name: String, ctx: FabDemoContext): FabDemoState = {
    ctx.publisher(LedgerStepAdvanced(state.ledgerSeq, name))
    state
  }

  private def buildAggregateStateFromWafers(
    wafers: Map[String, WaferInfo], ctx: FabDemoContext,
    reworkActive: Boolean, totalPass: Int, totalScrap: Int
  ): AggregateStateUpdated = {
    val srcLotId = ctx.scenario.scenarioId
    val rwkLotId = s"$srcLotId-RWK"
    val sourceLot = LotStateSnapshot(
      lotId = srcLotId,
      status = if (reworkActive || ctx.scenario.scenarioId.nonEmpty) "Active" else "Sealed",
      waferCount = ctx.scenario.lotSize,
      passCount = totalPass,
      scrapCount = totalScrap
    )
    val reworkLot = if (reworkActive) {
      Some(LotStateSnapshot(lotId = rwkLotId, status = "Active",
        waferCount = wafers.values.count(w => w.classification.contains("FAIL")), passCount = 0, scrapCount = 0))
    } else None

    val waferSnapshots = wafers.map { case (wid, info) =>
      val waferLot = info.classification match {
        case Some("FAIL") if info.reworkCount > 0 && reworkActive => rwkLotId
        case _ => srcLotId
      }
      WaferStateSnapshot(waferId = wid,
        status = if (info.classification.contains("SCRAP")) "Scrapped" else "Active",
        lotId = waferLot, classification = info.classification.getOrElse("Pending"), reworkCount = info.reworkCount)
    }.toSeq
    AggregateStateUpdated(sourceLot, reworkLot, waferSnapshots)
  }
}
