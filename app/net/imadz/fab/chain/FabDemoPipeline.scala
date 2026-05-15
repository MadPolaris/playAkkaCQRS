package net.imadz.fab.chain

import net.imadz.application.aggregates.LotProtocol.{LotCommand, LotConfirmation, SealLot}
import net.imadz.application.aggregates.WaferProtocol._
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
 * Fab Demo Pipeline — Rework scenario (photo-cell-5wafer) with Saga TCC split/merge.
 *
 * Stage functions delegate to [[PipelineStages]]; only rework-specific orchestration
 * (classify with rework loop, saga split/merge) remains here.
 */
object FabDemoPipeline {

  // ====================================================================
  // Pipeline runner
  // ====================================================================

  def runPipeline(initialState: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    implicit val ec: ExecutionContext = ctx.ec
    for {
      s1  <- PipelineStages.loadFoup(initialState, ctx)
      s2  <- PipelineStages.transport(s1, ctx, "STOCKER", "LITHO")
      s3  <- PipelineStages.atEquipment(s2, ctx, "LITHO", ctx.scenario.litho.equipmentId)
      s4  <- lithoProcess(s3, ctx)
      s5  <- PipelineStages.transport(s4, ctx, "LITHO", "CDSEM")
      s6  <- PipelineStages.atEquipment(s5, ctx, "CDSEM", ctx.scenario.cdSem.equipmentId)
      s7  <- PipelineStages.measure(s6, ctx, ctx.scenario.cdSem.equipmentId)
      s8  <- classify(s7, ctx)
      s9  <- maybeRework(s8, ctx)
      s10 <- PipelineStages.transport(s9, ctx, "CDSEM", "STOCKER")
      s11 <- PipelineStages.sealComplete(s10, ctx)
    } yield s11
  }

  // ====================================================================
  // Rework-specific: Litho process with rework recipe
  // ====================================================================
  private def lithoProcess(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    val recipeId = if (state.iteration == 0) "LITHO-28-001" else ctx.scenario.decision.reworkRecipeId
    PipelineStages.process(state, ctx, ctx.scenario.litho.equipmentId, recipeId, "LITHO")
  }

  // ====================================================================
  // Rework-specific: Classify with rework tracking
  // ====================================================================
  private def classify(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    val s = PipelineStages.emitLedger(state, "PhaseClassify: Decision Engine classifies wafers", ctx)
    ctx.publisher(GlobalStatusChanged("CLASSIFYING", "Decision Engine classifying", "PhaseClassify"))

    val decisionConfig = ctx.scenario.decision
    val maxRework = decisionConfig.maxReworkCount
    var updatedWafers = state.wafers
    var passWafers = Seq.empty[String]
    var reworkWafers = Seq.empty[String]
    var scrapWafers = Seq.empty[String]

    state.wafers.filter { case (_, w) => w.classification.isEmpty || w.classification.contains("FAIL") }.foreach { case (wid, info) =>
      val cdValue = info.cdValueHistory.lastOption.getOrElse(ctx.scenario.cdSemDetail.generateRandomCdValue())
      if (info.reworkCount > 0) {
        updatedWafers += wid -> info.copy(classification = Some("PASS"))
        passWafers :+= wid
        ctx.lotRef ! RecordWaferMeasured(wid, cdValue, ctx.ignoreLotReply)
        ctx.lotRef ! RecordWaferClassified(wid, "PASS", info.reworkCount, cdValue, ctx.ignoreLotReply)
        ctx.publisher(MeasurementResultEvent(wid, cdValue, "PASS", decisionConfig.upperSpecNm))
        ctx.publisher(DecisionMade(wid, "Rework → PASS", None))
      } else {
        val cls = PipelineStages.classifyCd(cdValue, decisionConfig)
        ctx.lotRef ! RecordWaferMeasured(wid, cdValue, ctx.ignoreLotReply)
        ctx.publisher(MeasurementResultEvent(wid, cdValue, cls, decisionConfig.upperSpecNm))
        cls match {
          case "PASS" =>
            updatedWafers += wid -> info.copy(classification = Some("PASS"))
            passWafers :+= wid
            ctx.lotRef ! RecordWaferClassified(wid, "PASS", 0, cdValue, ctx.ignoreLotReply)
            ctx.publisher(DecisionMade(wid, "PASS → Continue", None))
          case "BORDERLINE" =>
            if (info.reworkCount == 0) {
              updatedWafers += wid -> info.copy(classification = Some("PASS"))
              passWafers :+= wid
              ctx.lotRef ! RecordWaferClassified(wid, "PASS", 0, cdValue, ctx.ignoreLotReply)
              ctx.publisher(DecisionMade(wid, "BORDERLINE → Conditional Pass", None))
            } else {
              val nc = info.reworkCount + 1
              if (nc >= maxRework) {
                updatedWafers += wid -> info.copy(reworkCount = nc, classification = Some("SCRAP"))
                scrapWafers :+= wid
                ctx.lotRef ! RecordWaferClassified(wid, "SCRAP", nc, cdValue, ctx.ignoreLotReply)
                ctx.publisher(DecisionMade(wid, s"BORDERLINE → Max Rework($nc) → SCRAP", None))
                ctx.publisher(ScrapEvent(wid, s"Max rework($nc) exceeded"))
              } else {
                updatedWafers += wid -> info.copy(reworkCount = nc, classification = Some("FAIL"))
                reworkWafers :+= wid
                ctx.lotRef ! RecordWaferClassified(wid, "FAIL", nc, cdValue, ctx.ignoreLotReply)
                ctx.publisher(DecisionMade(wid, s"BORDERLINE → Rework (attempt $nc/$maxRework)", None))
              }
            }
          case "FAIL" =>
            val nc = info.reworkCount + 1
            if (nc >= maxRework) {
              updatedWafers += wid -> info.copy(reworkCount = nc, classification = Some("SCRAP"))
              scrapWafers :+= wid
              ctx.lotRef ! RecordWaferClassified(wid, "SCRAP", nc, cdValue, ctx.ignoreLotReply)
              ctx.publisher(DecisionMade(wid, s"FAIL → Max Rework($nc) → SCRAP", None))
              ctx.publisher(ScrapEvent(wid, s"Max rework($nc) exceeded"))
            } else {
              updatedWafers += wid -> info.copy(reworkCount = nc, classification = Some("FAIL"))
              reworkWafers :+= wid
              ctx.lotRef ! RecordWaferClassified(wid, "FAIL", nc, cdValue, ctx.ignoreLotReply)
              ctx.publisher(DecisionMade(wid, s"FAIL → Rework (attempt $nc/$maxRework)", None))
            }
          case "SCRAP" =>
            updatedWafers += wid -> info.copy(classification = Some("SCRAP"))
            scrapWafers :+= wid
            ctx.lotRef ! RecordWaferClassified(wid, "SCRAP", 0, cdValue, ctx.ignoreLotReply)
            ctx.publisher(DecisionMade(wid, "SCRAP → Terminate", None))
            ctx.publisher(ScrapEvent(wid, s"CD=$cdValue nm → SCRAP"))
        }
      }
    }

    scrapWafers.foreach { wid =>
      ctx.waferRefs.get(wid).foreach(ref => ref ! ScrapWafer("CD measurement out of spec", ctx.ignoreWaferReply))
    }

    val totalPass = updatedWafers.values.count(_.classification.contains("PASS"))
    val totalScrap = updatedWafers.values.count(_.classification.contains("SCRAP"))
    val totalRework = updatedWafers.values.count(w => w.classification.contains("FAIL") && w.reworkCount > 0)

    ctx.publisher(LotUpdated(ctx.scenario.scenarioId, ctx.scenario.lotSize, totalScrap,
      (1 to state.iteration + 1).map(i => s"Pass-$i").toList, totalPass, totalRework))
    ctx.publisher(PipelineStages.buildAggregateState(updatedWafers, ctx, totalPass, totalScrap, sourceLotArea = state.currentArea, childLotView = state.childLotView))

    if (reworkWafers.nonEmpty) {
      ctx.lotRef ! RecordWafersSplitForRework(reworkWafers.toSet, scrapWafers.toSet, state.iteration, ctx.ignoreLotReply)
      ctx.publisher(OrchestratorCommand(PipelineStages.cmdId(), "DECISION-ENGINE", "SplitLot",
        s"Split ${reworkWafers.size} wafers for rework: ${reworkWafers.mkString(",")}", reworkWafers))
    }

    Future.successful(s.copy(wafers = updatedWafers, passCount = totalPass, scrapCount = totalScrap,
      ledgerSeq = s.ledgerSeq + 1))
  }

  // ====================================================================
  // Rework sub-chain
  // ====================================================================
  private def maybeRework(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    val hasRework = state.wafers.values.exists(w => w.classification.contains("FAIL") && w.reworkCount > 0)
    if (!hasRework) {
      ctx.publisher(OrchestratorCommand(PipelineStages.cmdId(), "DECISION-ENGINE", "CompleteLot",
        s"All wafers resolved: ${state.passCount} PASS, ${state.scrapCount} SCRAP", Seq.empty))
      Future.successful(state)
    } else {
      val newIter = state.iteration + 1
      val reworkState = state.copy(iteration = newIter)
      implicit val ec: ExecutionContext = ctx.ec
      for {
        s1 <- sagaSplit(reworkState, ctx)
        s2 <- PipelineStages.transport(s1, ctx, "CDSEM", "LITHO")
        s3 <- PipelineStages.atEquipment(s2, ctx, "LITHO", ctx.scenario.litho.equipmentId)
        s4 <- lithoProcess(s3, ctx)
        s5 <- PipelineStages.transport(s4, ctx, "LITHO", "CDSEM")
        s6 <- PipelineStages.atEquipment(s5, ctx, "CDSEM", ctx.scenario.cdSem.equipmentId)
        s7 <- PipelineStages.measure(s6, ctx, ctx.scenario.cdSem.equipmentId)
        s8 <- classify(s7, ctx)
        s9 <- sagaMerge(s8, ctx)
      } yield s9
    }
  }

  // ====================================================================
  // Saga TCC stages
  // ====================================================================
  private def sagaSplit(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    val s = PipelineStages.emitLedger(state, "PhaseSplit: Saga SplitLot (TCC)", ctx)
    ctx.publisher(GlobalStatusChanged("SPLITTING", "Saga TCC split — rework wafers", "PhaseSplit"))

    val reworkWaferIds = state.wafers.filter { case (_, w) => w.classification.contains("FAIL") }.keys.toSeq
    val reworkWaferUUIDs: Set[Id] = reworkWaferIds.flatMap(ctx.waferUUIDs.get).toSet

    val sagaId = s"SAGA-SPLIT-${state.iteration}"
    ctx.publisher(SagaOperationEvent(sagaId, "SplitLot", "PREPARE",
      ctx.scenario.scenarioId, s"${ctx.scenario.scenarioId}-RWK", reworkWaferIds))
    ctx.publisher(FoupStateChanged(ctx.foupId, "SPLITTING", PipelineStages.activeCount(state), reworkWaferIds.size, "CDSEM",
      lotId = ctx.scenario.scenarioId, reworkLotId = s"${ctx.scenario.scenarioId}-RWK"))

    ctx.sagaTx(ctx.sourceLotId, ctx.reworkLotId, reworkWaferUUIDs).map { confirmation =>
      if (confirmation.error.isEmpty) {
        ctx.publisher(SagaOperationEvent(sagaId, "SplitLot", "COMMITTED",
          ctx.scenario.scenarioId, s"${ctx.scenario.scenarioId}-RWK", reworkWaferIds))
        ctx.publisher(OrchestratorCommand(PipelineStages.cmdId(), "SAGA-TCC", "SplitCompleted",
          s"TCC Split committed: ${reworkWaferIds.mkString(",")} → Rework Lot", reworkWaferIds))
        val rwkWaferIds = reworkWaferIds.toSet
        val updatedWafers = state.wafers.map { case (wid, info) =>
          if (rwkWaferIds.contains(wid)) wid -> info.copy(subLot = Some("rwk"))
          else wid -> info
        }
        val finalState = s.copy(wafers = updatedWafers, ledgerSeq = s.ledgerSeq + 1, spawnedChildLotKey = Some("rwk"), childLotView = Map("rwk" -> ("Active", reworkWaferIds.size)))
        ctx.publisher(PipelineStages.buildAggregateState(updatedWafers, ctx, state.passCount, state.scrapCount, sourceLotArea = state.currentArea, childLotView = state.childLotView))
        finalState
      } else {
        ctx.publisher(SagaOperationEvent(sagaId, "SplitLot", "FAILED", ctx.scenario.scenarioId, "", Seq.empty))
        s.copy(ledgerSeq = s.ledgerSeq + 1)
      }
    }(ctx.ec)
  }

  private def sagaMerge(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    val s = PipelineStages.emitLedger(state, "PhaseMerge: Saga MergeLot (TCC)", ctx)
    ctx.publisher(GlobalStatusChanged("MERGING", "Saga TCC merge — wafers → source lot", "PhaseMerge"))

    val reworkWaferIds = state.wafers.filter { case (_, w) => w.classification.contains("FAIL") }.keys.toSeq
    val mergeWaferUUIDs: Set[Id] = reworkWaferIds.flatMap(ctx.waferUUIDs.get).toSet

    val sagaId = s"SAGA-MERGE-${state.iteration}"
    ctx.publisher(SagaOperationEvent(sagaId, "MergeLot", "PREPARE",
      s"${ctx.scenario.scenarioId}-RWK", ctx.scenario.scenarioId, reworkWaferIds))

    ctx.sagaTx(ctx.reworkLotId, ctx.sourceLotId, mergeWaferUUIDs).map { confirmation =>
      if (confirmation.error.isEmpty) {
        ctx.publisher(SagaOperationEvent(sagaId, "MergeLot", "COMMITTED",
          s"${ctx.scenario.scenarioId}-RWK", ctx.scenario.scenarioId, reworkWaferIds))
        ctx.publisher(OrchestratorCommand(PipelineStages.cmdId(), "SAGA-TCC", "MergeCompleted",
          s"TCC Merge: ${reworkWaferIds.mkString(",")} → Source Lot", reworkWaferIds))
        val mergedWafers = state.wafers.map { case (wid, info) =>
          if (info.classification.contains("FAIL")) wid -> info.copy(classification = Some("PASS"), subLot = None)
          else wid -> info
        }
        val finalState = s.copy(wafers = mergedWafers, ledgerSeq = s.ledgerSeq + 1, spawnedChildLotKey = None, childLotView = Map("rwk" -> ("Merged", 0)))
        ctx.publisher(PipelineStages.buildAggregateState(mergedWafers, ctx, state.passCount, state.scrapCount, sourceLotArea = state.currentArea, childLotView = finalState.childLotView))
        finalState
      } else {
        ctx.publisher(SagaOperationEvent(sagaId, "MergeLot", "FAILED", ctx.scenario.scenarioId, "", Seq.empty))
        s.copy(ledgerSeq = s.ledgerSeq + 1)
      }
    }(ctx.ec)
  }

}
