package net.imadz.fab.chain

import net.imadz.application.aggregates.LotProtocol.{LotCommand, LotConfirmation, SealLot}
import net.imadz.application.aggregates.LotProtocol.LotCommand
import net.imadz.application.aggregates.LotProtocol._
import net.imadz.application.services.transactor.FabSagaProtocol.FabSagaConfirmation
import net.imadz.common.CommonTypes.Id
import net.imadz.fab.model.FabExecutionModel.{FabDemoContext, FabDemoState, WaferInfo}
import net.imadz.fab.events._
import net.imadz.fab.protocol._
import net.imadz.fab.scenario.FabSimulationScenario

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

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
      case LoadFoup             => PipelineStages.loadFoup(state, ctx)
      case Transport(from, to)  => PipelineStages.transport(state, ctx, from, to)
      case AtEquipment(area, eid) => PipelineStages.atEquipment(state, ctx, area, eid)
      case RunRecipe(eid, rid)  => PipelineStages.process(state, ctx, eid, rid, areaTypeFor(eid))
      case Measure(eid)         => PipelineStages.measure(state, ctx, eid)
      case Classify             => classifyStage(state, ctx)
      case SagaSplit(lotKey)    => sagaSplit(state, ctx, lotKey)
      case SagaMerge(lotKey)    => sagaMerge(state, ctx, lotKey)
      case ScrapWafers          => scrapWafers(state, ctx)
      case HoldWafers           => holdWafers(state, ctx)
      case ReleaseWafers        => releaseWafers(state, ctx)
      case WaitForReview(ms)    => waitForReview(state, ctx, ms)
      case SealComplete         => PipelineStages.sealComplete(state, ctx)
      case Branch(cond, t, f)   => if (cond(state)) runSequence(t, state, ctx) else runSequence(f, state, ctx)
    }
  }

  private def areaTypeFor(equipId: String): String =
    if (equipId.contains("LITHO")) "LITHO" else if (equipId.contains("CDSEM")) "METROLOGY" else equipId

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
      Measure(cdSemId), Classify, SagaSplit("scrap"), ScrapWafers,
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
  // Scenario-specific stages (not shared with FabDemoPipeline/FabFlowEngine)
  // ====================================================================

  private def classifyStage(state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] = {
    val s = PipelineStages.emitLedger(state, "PhaseClassify: Decision Engine", ctx)
    ctx.publisher(GlobalStatusChanged("CLASSIFYING", "Decision", "PhaseClassify"))
    val scenId = ctx.scenario.scenarioId
    var updatedWafers = state.wafers
    var scrapWafers = Seq.empty[String]
    var pilotPassed = state.pilotPassed
    var spawnedChild = state.spawnedChildLotKey

    state.wafers.filter { case (_, w) => w.classification.isEmpty }.foreach { case (wid, info) =>
      val cdValue = info.cdValueHistory.lastOption.getOrElse(32.0)
      val cls = PipelineStages.classifyCd(cdValue, ctx.scenario.decision)
      ctx.lotRef ! RecordWaferMeasured(ctx.waferUUIDs(wid), cdValue, ctx.ignoreLotReply)
      ctx.publisher(MeasurementResultEvent(wid, cdValue, cls, ctx.scenario.decision.upperSpecNm))

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
    ctx.publisher(PipelineStages.buildAggregateState(updatedWafers, ctx, totalPass, totalScrap, sourceLotArea = state.currentArea, childLotView = state.childLotView))

    Future.successful(s.copy(wafers = updatedWafers, passCount = totalPass, scrapCount = totalScrap,
      ledgerSeq = s.ledgerSeq + 1, pilotPassed = pilotPassed, spawnedChildLotKey = spawnedChild))
  }

  private def sagaSplit(state: FabDemoState, ctx: FabDemoContext, lotKey: String): Future[FabDemoState] = {
    val s = PipelineStages.emitLedger(state, s"PhaseSplit: Saga Split → $lotKey", ctx)
    ctx.publisher(GlobalStatusChanged("SPLITTING", s"Saga TCC split → $lotKey", "PhaseSplit"))
    val childLotId = ctx.childLotIds.getOrElse(lotKey, ctx.reworkLotId)
    val childLotRef = ctx.childLotRefs.getOrElse(lotKey, ctx.reworkLotRef)
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
    ctx.sagaTx(ctx.sourceLotId, childLotId, finalMoveIds, finalMoveNames).map { confirmation =>
      if (confirmation.error.isEmpty) {
        ctx.publisher(SagaOperationEvent(sagaId, "SplitLot", "COMMITTED", ctx.scenario.scenarioId, rwkLotName, finalMoveIds.toSeq.map(_.toString)))
        val updatedWafers = state.wafers.map { case (wid, info) =>
          if (finalMoveIds.contains(ctx.waferUUIDs.getOrElse(wid, UUID.nameUUIDFromBytes("none".getBytes))))
            wid -> info.copy(subLot = Some(lotKey))
          else wid -> info
        }
        val finalState = s.copy(wafers = updatedWafers, ledgerSeq = s.ledgerSeq + 1, childLotView = Map(lotKey -> ("Active", finalMoveIds.size)))
        ctx.publisher(PipelineStages.buildAggregateState(updatedWafers, ctx, state.passCount, state.scrapCount, sourceLotArea = state.currentArea, childLotView = finalState.childLotView))
        finalState
      } else {
        ctx.publisher(SagaOperationEvent(sagaId, "SplitLot", "FAILED", "", "", Seq.empty))
        s.copy(ledgerSeq = s.ledgerSeq + 1)
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
    ctx.sagaTx(childLotId, ctx.sourceLotId, finalMoveIds, finalMoveNames).map { confirmation =>
      if (confirmation.error.isEmpty) {
        ctx.publisher(SagaOperationEvent(sagaId, "MergeLot", "COMMITTED", rwkLotName, ctx.scenario.scenarioId, finalMoveIds.toSeq.map(_.toString)))
        val mergedWafers = state.wafers.map { case (wid, info) =>
          if (finalMoveIds.contains(ctx.waferUUIDs.getOrElse(wid, java.util.UUID.nameUUIDFromBytes("none".getBytes))))
            wid -> info.copy(subLot = None, classification = Some("PASS"))
          else wid -> info
        }
        val finalState = s.copy(wafers = mergedWafers, ledgerSeq = s.ledgerSeq + 1, childLotView = Map(lotKey -> ("Merged", 0)))
        ctx.publisher(PipelineStages.buildAggregateState(mergedWafers, ctx, state.passCount, state.scrapCount, sourceLotArea = state.currentArea, childLotView = finalState.childLotView))
        finalState
      } else {
        val errMsg = confirmation.error.getOrElse("unknown")
        ctx.publisher(SagaOperationEvent(sagaId, "MergeLot", s"FAILED: $errMsg", rwkLotName, ctx.scenario.scenarioId, moveIds.toSeq.map(_.toString)))
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

  private def waitForReview(state: FabDemoState, ctx: FabDemoContext, durationMs: Long): Future[FabDemoState] = {
    val s = PipelineStages.emitLedger(state, s"PhaseReview: Engineer review (${durationMs / 1000}s)", ctx)
    ctx.publisher(OrchestratorCommand(PipelineStages.cmdId(), "ENGINEER-REVIEW", "Review",
      s"Reviewing held wafers (${durationMs / 1000}s)", state.wafers.filter(_._2.subLot.contains("hold")).keys.toSeq))
    Future { Thread.sleep(durationMs); s.copy(ledgerSeq = s.ledgerSeq + 1) }(ctx.ec)
  }
}
