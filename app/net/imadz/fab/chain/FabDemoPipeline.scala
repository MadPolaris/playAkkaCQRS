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

import akka.util.Timeout
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
      s9b <- handleScrap(s9, ctx)
      _   <- validateNoFailWafers(s9b)
      s10 <- PipelineStages.transport(s9b, ctx, "CDSEM", "STOCKER")
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
        ctx.lotRef ! RecordWaferMeasured(ctx.waferUUIDs(wid), cdValue, ctx.ignoreLotReply)
        ctx.lotRef ! RecordWaferClassified(ctx.waferUUIDs(wid),"PASS", info.reworkCount, cdValue, ctx.ignoreLotReply)
        ctx.publisher(MeasurementResultEvent(wid, cdValue, "PASS", decisionConfig.upperSpecNm))
        ctx.publisher(DecisionMade(wid, "Rework → PASS", None))
      } else {
        val cls = PipelineStages.classifyCd(cdValue, decisionConfig)
        ctx.lotRef ! RecordWaferMeasured(ctx.waferUUIDs(wid), cdValue, ctx.ignoreLotReply)
        ctx.publisher(MeasurementResultEvent(wid, cdValue, cls, decisionConfig.upperSpecNm))
        cls match {
          case "PASS" =>
            updatedWafers += wid -> info.copy(classification = Some("PASS"))
            passWafers :+= wid
            ctx.lotRef ! RecordWaferClassified(ctx.waferUUIDs(wid),"PASS", 0, cdValue, ctx.ignoreLotReply)
            ctx.publisher(DecisionMade(wid, "PASS → Continue", None))
          case "BORDERLINE" =>
            if (info.reworkCount == 0) {
              updatedWafers += wid -> info.copy(classification = Some("PASS"))
              passWafers :+= wid
              ctx.lotRef ! RecordWaferClassified(ctx.waferUUIDs(wid),"PASS", 0, cdValue, ctx.ignoreLotReply)
              ctx.publisher(DecisionMade(wid, "BORDERLINE → Conditional Pass", None))
            } else {
              val nc = info.reworkCount + 1
              if (nc >= maxRework) {
                updatedWafers += wid -> info.copy(reworkCount = nc, classification = Some("SCRAP"))
                scrapWafers :+= wid
                ctx.lotRef ! RecordWaferClassified(ctx.waferUUIDs(wid),"SCRAP", nc, cdValue, ctx.ignoreLotReply)
                ctx.publisher(DecisionMade(wid, s"BORDERLINE → Max Rework($nc) → SCRAP", None))
                ctx.publisher(ScrapEvent(wid, s"Max rework($nc) exceeded"))
              } else {
                updatedWafers += wid -> info.copy(reworkCount = nc, classification = Some("FAIL"))
                reworkWafers :+= wid
                ctx.lotRef ! RecordWaferClassified(ctx.waferUUIDs(wid),"FAIL", nc, cdValue, ctx.ignoreLotReply)
                ctx.publisher(DecisionMade(wid, s"BORDERLINE → Rework (attempt $nc/$maxRework)", None))
              }
            }
          case "FAIL" =>
            val nc = info.reworkCount + 1
            if (nc >= maxRework) {
              updatedWafers += wid -> info.copy(reworkCount = nc, classification = Some("SCRAP"))
              scrapWafers :+= wid
              ctx.lotRef ! RecordWaferClassified(ctx.waferUUIDs(wid),"SCRAP", nc, cdValue, ctx.ignoreLotReply)
              ctx.publisher(DecisionMade(wid, s"FAIL → Max Rework($nc) → SCRAP", None))
              ctx.publisher(ScrapEvent(wid, s"Max rework($nc) exceeded"))
            } else {
              updatedWafers += wid -> info.copy(reworkCount = nc, classification = Some("FAIL"))
              reworkWafers :+= wid
              ctx.lotRef ! RecordWaferClassified(ctx.waferUUIDs(wid),"FAIL", nc, cdValue, ctx.ignoreLotReply)
              ctx.publisher(DecisionMade(wid, s"FAIL → Rework (attempt $nc/$maxRework)", None))
            }
          case "SCRAP" =>
            updatedWafers += wid -> info.copy(classification = Some("SCRAP"))
            scrapWafers :+= wid
            ctx.lotRef ! RecordWaferClassified(ctx.waferUUIDs(wid),"SCRAP", 0, cdValue, ctx.ignoreLotReply)
            ctx.publisher(DecisionMade(wid, "SCRAP → Terminate", None))
            ctx.publisher(ScrapEvent(wid, s"CD=$cdValue nm → SCRAP"))
        }
      }
    }


    val totalPass = updatedWafers.values.count(_.classification.contains("PASS"))
    val totalScrap = updatedWafers.values.count(_.classification.contains("SCRAP"))
    val totalRework = updatedWafers.values.count(w => w.classification.contains("FAIL") && w.reworkCount > 0)

    ctx.publisher(LotUpdated(ctx.scenario.scenarioId, ctx.scenario.lotSize, totalScrap,
      (1 to state.iteration + 1).map(i => s"Pass-$i").toList, totalPass, totalRework))

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
      val reworkCtx = ctx.copy(lotRef = ctx.reworkLotRef)
      for {
        s1 <- sagaSplit(reworkState, ctx)
        s2 <- PipelineStages.transport(s1, reworkCtx, "CDSEM", "LITHO")
        s3 <- PipelineStages.atEquipment(s2, reworkCtx, "LITHO", ctx.scenario.litho.equipmentId)
        s4 <- lithoProcess(s3, reworkCtx)
        s5 <- PipelineStages.transport(s4, reworkCtx, "LITHO", "CDSEM")
        s6 <- PipelineStages.atEquipment(s5, reworkCtx, "CDSEM", ctx.scenario.cdSem.equipmentId)
        s7 <- PipelineStages.measure(s6, reworkCtx, ctx.scenario.cdSem.equipmentId)
        s8 <- classify(s7, reworkCtx)
        s9 <- sagaMerge(s8, ctx)
      } yield s9
    }
  }

  private def validateNoFailWafers(state: FabDemoState): Future[Unit] = {
    val failWafers = state.wafers.collect { case (wid, w) if w.classification.contains("FAIL") => wid }.toSeq
    if (failWafers.nonEmpty)
      Future.failed(new IllegalStateException(
        s"Pipeline halted: ${failWafers.size} unresolved FAIL wafers: ${failWafers.mkString(",")}"))
    else
      Future.successful(())
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

    ctx.sagaTx(ctx.sourceLotId, ctx.reworkLotId, reworkWaferUUIDs, reworkWaferIds.toSet).flatMap { confirmation =>
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
        Future.successful(finalState)
      } else {
        val errMsg = confirmation.error.getOrElse("unknown")
        ctx.publisher(SagaOperationEvent(sagaId, "SplitLot", s"FAILED: $errMsg", ctx.scenario.scenarioId, "", Seq.empty))
        Future.failed(new IllegalStateException(s"Saga $sagaId SplitLot failed: $errMsg"))
      }
    }(ctx.ec)
  }

  private def sagaMerge(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    implicit val timeout: Timeout = 10.seconds
    val s = PipelineStages.emitLedger(state, "PhaseMerge: Saga MergeLot (TCC)", ctx)
    ctx.publisher(GlobalStatusChanged("MERGING", "Saga TCC merge — wafers → source lot", "PhaseMerge"))

    val uuidToName: Map[Id, String] = ctx.waferUUIDs.map(_.swap)
    ctx.reworkLotRef.ask[LotConfirmation](ref => GetLotState(ref)).flatMap { reworkState =>
      val passWaferUUIDs: Seq[Id] = reworkState.waferClassifications.collect {
        case (id, cls) if cls == "PASS" => id
      }.toSeq

      if (passWaferUUIDs.isEmpty) {
        ctx.publisher(OrchestratorCommand(PipelineStages.cmdId(), "SAGA-TCC", "MergeSkipped",
          "No PASS wafers in rework lot to merge", Seq.empty))
        Future.successful(s.copy(ledgerSeq = s.ledgerSeq + 1))
      } else {
        val passWaferNames = passWaferUUIDs.flatMap(uuidToName.get)
        val sagaId = s"SAGA-MERGE-${state.iteration}"
        ctx.publisher(SagaOperationEvent(sagaId, "MergeLot", "PREPARE",
          s"${ctx.scenario.scenarioId}-RWK", ctx.scenario.scenarioId, passWaferNames.toSeq))

        ctx.sagaTx(ctx.reworkLotId, ctx.sourceLotId, passWaferUUIDs.toSet, passWaferNames.toSet).flatMap { confirmation =>
          if (confirmation.error.isEmpty) {
            ctx.publisher(SagaOperationEvent(sagaId, "MergeLot", "COMMITTED",
              s"${ctx.scenario.scenarioId}-RWK", ctx.scenario.scenarioId, passWaferNames.toSeq))
            ctx.publisher(OrchestratorCommand(PipelineStages.cmdId(), "SAGA-TCC", "MergeCompleted",
              s"TCC Merge: ${passWaferNames.mkString(",")} → Source Lot", passWaferNames.toSeq))
            passWaferNames.foreach { name =>
              state.wafers.get(name).foreach { info =>
                val cdValue = info.cdValueHistory.lastOption.getOrElse(0.0)
                ctx.lotRef ! RecordWaferClassified(ctx.waferUUIDs(name), "PASS", info.reworkCount, cdValue, ctx.ignoreLotReply)
              }
            }
            val nameSet = passWaferNames.toSet
            val mergedWafers = state.wafers.map { case (wid, info) =>
              if (nameSet.contains(wid)) wid -> info.copy(classification = Some("PASS"), subLot = None)
              else wid -> info
            }
            val finalState = s.copy(wafers = mergedWafers, ledgerSeq = s.ledgerSeq + 1, spawnedChildLotKey = None, childLotView = Map("rwk" -> ("Merged", 0)))
            Future.successful(finalState)
          } else {
            val errMsg = confirmation.error.getOrElse("unknown")
            ctx.publisher(SagaOperationEvent(sagaId, "MergeLot", s"FAILED: $errMsg", ctx.scenario.scenarioId, "", Seq.empty))
            Future.failed(new IllegalStateException(s"Saga $sagaId MergeLot failed: $errMsg"))
          }
        }(ctx.ec)
      }
    }(ctx.ec)
  }

  // ====================================================================
  // Scrap handling
  // ====================================================================
  private def handleScrap(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    val scrapWafers = state.wafers.filter { case (_, w) => w.classification.contains("SCRAP") }.keys.toSeq
    if (scrapWafers.isEmpty || ctx.scrapLotId.isEmpty) Future.successful(state)
    else sagaScrap(state, ctx, scrapWafers)
  }

  private def sagaScrap(state: FabDemoState, ctx: FabDemoContext, scrapWaferIds: Seq[String]): Future[FabDemoState] = {
    val s = PipelineStages.emitLedger(state, "PhaseScrap: Saga Scrap (TCC)", ctx)
    ctx.publisher(GlobalStatusChanged("SCRAPPING", "Saga TCC — scrap wafers", "PhaseScrap"))

    val scrapLotId = ctx.scrapLotId.get
    val scrapWaferUUIDs: Set[Id] = scrapWaferIds.flatMap(ctx.waferUUIDs.get).toSet

    val scrapLotIdStr = scrapLotId.toString
    ctx.publisher(SagaOperationEvent("SAGA-SCRAP", "ScrapLot", "PREPARE",
      ctx.scenario.scenarioId, scrapLotIdStr, scrapWaferIds))

    ctx.sagaTx(ctx.sourceLotId, scrapLotId, scrapWaferUUIDs, scrapWaferIds.toSet).flatMap { confirmation =>
      if (confirmation.error.isEmpty) {
        ctx.publisher(SagaOperationEvent("SAGA-SCRAP", "ScrapLot", "COMMITTED",
          ctx.scenario.scenarioId, scrapLotIdStr, scrapWaferIds))
        ctx.publisher(OrchestratorCommand(PipelineStages.cmdId(), "SAGA-TCC", "ScrapCompleted",
          s"TCC Scrap: ${scrapWaferIds.mkString(",")} → Scrap Lot", scrapWaferIds))
        val scrapSet = scrapWaferIds.toSet
        val updatedWafers = state.wafers.map { case (wid, info) =>
          if (scrapSet.contains(wid)) wid -> info.copy(subLot = Some("scrap"))
          else wid -> info
        }
        val finalState = s.copy(wafers = updatedWafers, ledgerSeq = s.ledgerSeq + 1,
          childLotView = state.childLotView + ("scrap" -> ("Scrapped", scrapWaferIds.size)))
        Future.successful(finalState)
      } else {
        val errMsg = confirmation.error.getOrElse("unknown")
        ctx.publisher(SagaOperationEvent("SAGA-SCRAP", "ScrapLot", s"FAILED: $errMsg", ctx.scenario.scenarioId, "", Seq.empty))
        Future.failed(new IllegalStateException(s"Saga SAGA-SCRAP ScrapLot failed: $errMsg"))
      }
    }(ctx.ec)
  }

}
