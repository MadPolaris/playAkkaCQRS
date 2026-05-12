package net.imadz.fab.orchestration

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.cluster.sharding.typed.scaladsl.EntityRef
import net.imadz.application.aggregates.process.FabProcessProtocol.ProcessConfirmation
import net.imadz.application.aggregates.process.FabProcessProtocol._
import net.imadz.fab.events._
import net.imadz.fab.protocol._
import net.imadz.fab.scenario.FabSimulationScenario
import net.imadz.fab.simulation._

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
 * Non-persistent simulation coordinator — drives the 14-phase manufacturing process,
 * communicates with equipment simulators, and sends domain commands to
 * [[net.imadz.application.aggregates.process.FabProcessAggregate]] for event sourcing.
 *
 * Simulation events (EquipmentStateChanged, FoupInTransit, etc.) are published
 * directly to the WebSocket hub. Domain events flow through the aggregate →
 * EventStream → FabDemoEventBridge → WebSocket hub.
 */
object FabSimulationCoordinator {

  sealed trait SimCommand
  case class StartScenario(scenario: FabSimulationScenario, replyTo: ActorRef[SimResult]) extends SimCommand
  case class AdjustSpeed(multiplier: Double) extends SimCommand
  case class InjectFault(equipmentId: String, faultType: String) extends SimCommand
  case object Pause extends SimCommand
  case object Resume extends SimCommand

  case class SimResult(success: Boolean, message: String)

  private case class RunPhase(ctx: RunContext) extends SimCommand
  private case class PhaseResult(ctx: RunContext, event: EquipmentEvent) extends SimCommand
  private case class PhaseFailed(ctx: RunContext, error: Throwable) extends SimCommand

  // ---- Engine Phases (identical to FabSimulationEngine) ----

  sealed trait EnginePhase
  case object PhaseLoad                                         extends EnginePhase
  case class PhaseTransportToLitho(foupId: String)              extends EnginePhase
  case object PhaseAtLitho                                      extends EnginePhase
  case object PhaseLithoProcess                                 extends EnginePhase
  case class PhaseTransportToCdSem(foupId: String)              extends EnginePhase
  case object PhaseAtCdSem                                      extends EnginePhase
  case object PhaseCdSemMeasure                                 extends EnginePhase
  case class PhaseClassify(previousCdValues: Map[String, Double]) extends EnginePhase
  case class PhaseSplit(foupId: String, reworkWafers: Seq[String],
                        scrapWafers: Seq[String])               extends EnginePhase
  case class PhaseReworkTransport(foupId: String)               extends EnginePhase
  case class PhaseReturnToStocker(foupId: String)               extends EnginePhase
  case object PhaseComplete                                     extends EnginePhase

  case class WaferInfo(
    waferId: String,
    reworkCount: Int = 0,
    cdValueHistory: List[Double] = Nil,
    classification: Option[String] = None
  )

  case class RunContext(
    scenario: FabSimulationScenario,
    foupId: String,
    processId: String,
    phase: EnginePhase,
    wafers: Map[String, WaferInfo],
    passCount: Int = 0,
    scrapCount: Int = 0,
    iteration: Int = 0
  )

  // ---- Behavior ----

  def apply(
    publisher: FabSimulationEvent => Unit,
    adapter: ActorEquipmentAdapter,
    processRef: EntityRef[FabProcessCommand]
  )(implicit ec: ExecutionContext): Behavior[SimCommand] =
    Behaviors.setup { ctx =>
      // Fire-and-forget reply target — ignores ProcessConfirmation replies
      implicit val ignoreReply: ActorRef[ProcessConfirmation] =
        ctx.spawnAnonymous(Behaviors.ignore[ProcessConfirmation])
      idle(ctx, publisher, adapter, processRef, ignoreReply, speedMultiplier = 1.0, running = false)
    }

  private def idle(
    ctx: ActorContext[SimCommand],
    publisher: FabSimulationEvent => Unit,
    adapter: ActorEquipmentAdapter,
    processRef: EntityRef[FabProcessCommand],
    ignoreReply: ActorRef[ProcessConfirmation],
    speedMultiplier: Double,
    running: Boolean
  ): Behavior[SimCommand] = Behaviors.receiveMessage {
    case StartScenario(scenario, replyTo) if !running =>
      ledgerSeq = 0
      val processId = UUID.randomUUID().toString

      // Tell aggregate to start process (fire-and-forget)
      processRef ! StartProcess(scenario.scenarioId, scenario.waferIds.toSet, scenario.lotSize, ignoreReply)

      // Simulation events — published directly
      publisher(DemoStarted(scenario.scenarioId, scenario.name, scenario.lotSize, scenario.waferIds))
      spawnSimulators(ctx, scenario, adapter, publisher, speedMultiplier)

      val foupId = s"FOUP-${scenario.scenarioId}"
      val wafers = scenario.waferIds.map(wid => wid -> WaferInfo(wid)).toMap
      val runCtx = RunContext(scenario, foupId, processId, PhaseLoad, wafers)

      replyTo ! SimResult(success = true, s"Scenario '${scenario.name}' started")
      ctx.self ! RunPhase(runCtx)
      idle(ctx, publisher, adapter, processRef, ignoreReply, speedMultiplier, running = true)

    case StartScenario(_, replyTo) =>
      replyTo ! SimResult(success = false, "A scenario is already running. Reset the page to restart.")
      Behaviors.same

    case RunPhase(runCtx) =>
      runPhase(ctx, runCtx, publisher, adapter, processRef, ignoreReply, speedMultiplier)

    case PhaseResult(runCtx, event) =>
      handlePhaseResult(ctx, runCtx, event, publisher, adapter, processRef, ignoreReply, speedMultiplier)

    case PhaseFailed(runCtx, error) =>
      publisher(ProcessingCompleted("engine", "", success = false, error.getMessage))
      Behaviors.same

    case Pause  => Behaviors.same
    case Resume => Behaviors.same
    case _      => Behaviors.same
  }

  // ---- Spawn Simulators ----

  private def spawnSimulators(
    ctx: ActorContext[SimCommand],
    scenario: FabSimulationScenario,
    adapter: ActorEquipmentAdapter,
    publisher: FabSimulationEvent => Unit,
    speedMultiplier: Double
  ): Unit = {
    val lithoActor = ctx.spawn(
      new LithographySimulator(scenario.lithoDetail)(scenario.litho),
      s"litho-${scenario.scenarioId}"
    )
    val cdSemActor = ctx.spawn(
      new CdSemSimulator(scenario.cdSemDetail)(scenario.cdSem),
      s"cdsem-${scenario.scenarioId}"
    )
    val amhsActor = ctx.spawn(
      new AmhsSimulator()(scenario.amhs, speedMultiplier),
      s"amhs-${scenario.scenarioId}"
    )
    val stockerActor = ctx.spawn(
      new StockerSimulator()(scenario.stocker),
      s"stocker-${scenario.scenarioId}"
    )

    adapter.registerSimulator(scenario.litho.equipmentId, lithoActor)
    adapter.registerSimulator(scenario.cdSem.equipmentId, cdSemActor)
    adapter.registerSimulator("AMHS", amhsActor)
    adapter.registerSimulator(scenario.stocker.equipmentId, stockerActor)

    publisher(EquipmentStateChanged(scenario.litho.equipmentId, "LITHO", "Idle", None))
    publisher(EquipmentStateChanged(scenario.cdSem.equipmentId, "METROLOGY", "Idle", None))
    publisher(EquipmentStateChanged(scenario.stocker.equipmentId, "STOCKER", "Idle", None))
  }

  // ====================================================================
  // Phase Runner
  // ====================================================================

  private def runPhase(
    ctx: ActorContext[SimCommand],
    runCtx: RunContext,
    publisher: FabSimulationEvent => Unit,
    adapter: ActorEquipmentAdapter,
    processRef: EntityRef[FabProcessCommand],
    ignoreReply: ActorRef[ProcessConfirmation],
    speedMultiplier: Double
  ): Behavior[SimCommand] = {
    emitLedgerStep(runCtx, publisher)
    val (statusCode, statusDetail) = phaseStatus(runCtx.phase)
    publisher(GlobalStatusChanged(statusCode, statusDetail, runCtx.phase.getClass.getSimpleName.replace("$","")))

    runCtx.phase match {

      // ---- PhaseLoad ----
      case PhaseLoad =>
        val foupId = runCtx.foupId
        processRef ! RecordFoupLoaded(foupId, runCtx.scenario.stocker.equipmentId, ignoreReply)
        publisher(OrchestratorCommand(cmdId(), "STOCKER-01", "LoadFoup",
          s"Load $foupId with ${runCtx.wafers.size} wafers", runCtx.scenario.waferIds))
        publisher(FoupStateChanged(foupId, "LOADING", activeCount(runCtx), 0, "STOCKER",
          lotId = runCtx.scenario.scenarioId))
        publisher(FoupArrivedAtPort(foupId, runCtx.scenario.stocker.equipmentId, "STOCKER-PORT-1"))
        publisher(buildAggregateState(runCtx, reworkActive = false))
        ctx.self ! RunPhase(runCtx.copy(phase = PhaseTransportToLitho(foupId)))
        Behaviors.same

      // ---- PhaseTransportToLitho ----
      case PhaseTransportToLitho(foupId) =>
        val scaledMs = scale(runCtx.scenario.amhs.routes("STOCKER" -> "LITHO"), speedMultiplier).toMillis
        processRef ! RecordTransportStarted(foupId, "STOCKER", "LITHO", scaledMs, ignoreReply)
        publisher(OrchestratorCommand(cmdId(), "AMHS", "TransferFoup",
          s"Transport $foupId: STOCKER → LITHO (${scaledMs}ms)", runCtx.scenario.waferIds))
        publisher(FoupStateChanged(foupId, "IN_TRANSIT", activeCount(runCtx), reworkCount(runCtx), "AMHS",
          lotId = runCtx.scenario.scenarioId))
        publisher(FoupInTransit(foupId, "STOCKER", "LITHO", scaledMs / 2))
        ctx.pipeToSelf(adapter.sendCommand("AMHS",
          TransferFoup(foupId, "STOCKER", "LITHO"))) {
          case Success(evt) => PhaseResult(runCtx, evt)
          case Failure(err) => PhaseFailed(runCtx, err)
        }
        Behaviors.same

      // ---- PhaseAtLitho ----
      case PhaseAtLitho =>
        val foupId = runCtx.foupId
        processRef ! RecordTransportCompleted(foupId, runCtx.scenario.litho.equipmentId, ignoreReply)
        publisher(FoupArrivedAtPort(foupId, runCtx.scenario.litho.equipmentId, "LITHO-PORT-1"))
        publisher(FoupStateChanged(foupId, "AT_EQUIPMENT", activeCount(runCtx), reworkCount(runCtx), "LITHO",
          lotId = runCtx.scenario.scenarioId))
        publisher(EquipmentStateChanged(runCtx.scenario.litho.equipmentId, "LITHO", "Idle", None))
        ctx.self ! RunPhase(runCtx.copy(phase = PhaseLithoProcess))
        Behaviors.same

      // ---- PhaseLithoProcess ----
      case PhaseLithoProcess =>
        val litho = runCtx.scenario.litho
        val scaledMs = scale(litho.processingTime, speedMultiplier).toMillis
        val iter = runCtx.iteration
        val recipeId = if (iter == 0) "LITHO-28-001" else runCtx.scenario.decision.reworkRecipeId

        processRef ! RecordEquipmentJobStarted(litho.equipmentId, recipeId, ignoreReply)
        publisher(OrchestratorCommand(cmdId(), litho.equipmentId, "ProcessRecipe",
          s"Run $recipeId on ${activeCount(runCtx)} wafers (${scaledMs}ms)", unresolvedIds(runCtx)))
        publisher(EquipmentStateChanged(litho.equipmentId, "LITHO", "Busy", Some(s"litho-job-$iter")))
        publisher(ProcessingStarted(litho.equipmentId, recipeId, scaledMs))
        ctx.pipeToSelf(adapter.sendCommand(litho.equipmentId, ProcessRecipe(recipeId))) {
          case Success(evt) => PhaseResult(runCtx, evt)
          case Failure(err) => PhaseFailed(runCtx, err)
        }
        Behaviors.same

      // ---- PhaseTransportToCdSem ----
      case PhaseTransportToCdSem(foupId) =>
        val litho = runCtx.scenario.litho
        val scaledMs = scale(runCtx.scenario.amhs.routes("LITHO" -> "CDSEM"), speedMultiplier).toMillis

        processRef ! RecordEquipmentJobCompleted(litho.equipmentId, s"litho-job-${runCtx.iteration}", success = true, ignoreReply)
        publisher(ProcessingCompleted(litho.equipmentId, s"litho-job-${runCtx.iteration}", success = true, ""))
        publisher(EquipmentStateChanged(litho.equipmentId, "LITHO", "Idle", None))
        publisher(OrchestratorCommand(cmdId(), "AMHS", "TransferFoup",
          s"Transport $foupId: LITHO → CDSEM (${scaledMs}ms)", unresolvedIds(runCtx)))
        publisher(FoupStateChanged(foupId, "IN_TRANSIT", activeCount(runCtx), reworkCount(runCtx), "AMHS",
          lotId = runCtx.scenario.scenarioId))
        publisher(FoupInTransit(foupId, "LITHO", "CDSEM", scaledMs / 2))
        ctx.pipeToSelf(adapter.sendCommand("AMHS",
          TransferFoup(foupId, "LITHO", "CDSEM"))) {
          case Success(evt) => PhaseResult(runCtx, evt)
          case Failure(err) => PhaseFailed(runCtx, err)
        }
        Behaviors.same

      // ---- PhaseAtCdSem ----
      case PhaseAtCdSem =>
        val foupId = runCtx.foupId
        processRef ! RecordTransportCompleted(foupId, runCtx.scenario.cdSem.equipmentId, ignoreReply)
        publisher(FoupArrivedAtPort(foupId, runCtx.scenario.cdSem.equipmentId, "CDSEM-PORT-1"))
        publisher(FoupStateChanged(foupId, "AT_EQUIPMENT", activeCount(runCtx), reworkCount(runCtx), "CDSEM",
          lotId = runCtx.scenario.scenarioId))
        ctx.self ! RunPhase(runCtx.copy(phase = PhaseCdSemMeasure))
        Behaviors.same

      // ---- PhaseCdSemMeasure ----
      case PhaseCdSemMeasure =>
        val cdSem = runCtx.scenario.cdSem
        val scaledMs = scale(cdSem.processingTime, speedMultiplier).toMillis

        processRef ! RecordEquipmentJobStarted(cdSem.equipmentId, "CD-MEASURE-001", ignoreReply)
        publisher(OrchestratorCommand(cmdId(), cdSem.equipmentId, "ProcessRecipe",
          s"Measure CD on ${activeCount(runCtx)} wafers (${scaledMs}ms)", unresolvedIds(runCtx)))
        publisher(EquipmentStateChanged(cdSem.equipmentId, "METROLOGY", "Busy", Some(s"metrology-job-${runCtx.iteration}")))
        publisher(ProcessingStarted(cdSem.equipmentId, "CD-MEASURE-001", scaledMs))
        ctx.pipeToSelf(adapter.sendCommand(cdSem.equipmentId, ProcessRecipe("CD-MEASURE-001"))) {
          case Success(evt) => PhaseResult(runCtx, evt)
          case Failure(err) => PhaseFailed(runCtx, err)
        }
        Behaviors.same

      // ---- PhaseClassify ----
      case PhaseClassify(previousCdValues) =>
        val cdSem = runCtx.scenario.cdSem
        processRef ! RecordEquipmentJobCompleted(cdSem.equipmentId, s"metrology-job-${runCtx.iteration}", success = true, ignoreReply)
        publisher(ProcessingCompleted(cdSem.equipmentId, s"metrology-job-${runCtx.iteration}", success = true, ""))
        publisher(EquipmentStateChanged(cdSem.equipmentId, "METROLOGY", "Idle", None))

        val decisionConfig = runCtx.scenario.decision
        val maxRework = decisionConfig.maxReworkCount
        var updatedWafers = runCtx.wafers
        var passWafers = Seq.empty[String]
        var reworkWafers = Seq.empty[String]
        var scrapWafers = Seq.empty[String]

        unresolvedIds(runCtx).foreach { wid =>
          val info = updatedWafers(wid)
          val cdValue = previousCdValues.getOrElse(wid, generateCdValue(runCtx.scenario.cdSemDetail))

          if (info.reworkCount > 0) {
            updatedWafers += wid -> info.copy(cdValueHistory = info.cdValueHistory :+ cdValue,
              classification = Some("PASS"))
            passWafers :+= wid
            processRef ! RecordWaferMeasured(wid, cdValue, ignoreReply)
            processRef ! RecordWaferClassified(wid, "PASS", info.reworkCount, cdValue, ignoreReply)
            publisher(MeasurementResultEvent(wid, cdValue, "PASS", decisionConfig.upperSpecNm))
            publisher(DecisionMade(wid, "Rework → PASS", None))
          } else {
            val cls = classifyCd(cdValue, decisionConfig)
            processRef ! RecordWaferMeasured(wid, cdValue, ignoreReply)
            publisher(MeasurementResultEvent(wid, cdValue, cls, decisionConfig.upperSpecNm))

            cls match {
              case "PASS" =>
                updatedWafers += wid -> info.copy(cdValueHistory = info.cdValueHistory :+ cdValue,
                  classification = Some("PASS"))
                passWafers :+= wid
                processRef ! RecordWaferClassified(wid, "PASS", 0, cdValue, ignoreReply)
                publisher(DecisionMade(wid, "PASS → Continue", None))

              case "BORDERLINE" =>
                if (info.reworkCount == 0) {
                  updatedWafers += wid -> info.copy(cdValueHistory = info.cdValueHistory :+ cdValue,
                    classification = Some("PASS"))
                  passWafers :+= wid
                  processRef ! RecordWaferClassified(wid, "PASS", 0, cdValue, ignoreReply)
                  publisher(DecisionMade(wid, "BORDERLINE → Conditional Pass", None))
                } else {
                  val newCount = info.reworkCount + 1
                  if (newCount >= maxRework) {
                    updatedWafers += wid -> info.copy(reworkCount = newCount,
                      cdValueHistory = info.cdValueHistory :+ cdValue,
                      classification = Some("SCRAP"))
                    scrapWafers :+= wid
                    processRef ! RecordWaferClassified(wid, "SCRAP", newCount, cdValue, ignoreReply)
                    publisher(DecisionMade(wid, s"BORDERLINE → Max Rework($newCount) → SCRAP", None))
                    publisher(ScrapEvent(wid, s"Max rework($newCount) exceeded"))
                  } else {
                    updatedWafers += wid -> info.copy(reworkCount = newCount,
                      cdValueHistory = info.cdValueHistory :+ cdValue,
                      classification = Some("FAIL"))
                    reworkWafers :+= wid
                    processRef ! RecordWaferClassified(wid, "FAIL", newCount, cdValue, ignoreReply)
                    publisher(DecisionMade(wid, s"BORDERLINE → Rework (attempt $newCount/$maxRework)", None))
                  }
                }

              case "FAIL" =>
                val newCount = info.reworkCount + 1
                if (newCount >= maxRework) {
                  updatedWafers += wid -> info.copy(reworkCount = newCount,
                    cdValueHistory = info.cdValueHistory :+ cdValue,
                    classification = Some("SCRAP"))
                  scrapWafers :+= wid
                  processRef ! RecordWaferClassified(wid, "SCRAP", newCount, cdValue, ignoreReply)
                  publisher(DecisionMade(wid, s"FAIL → Max Rework($newCount) → SCRAP", None))
                  publisher(ScrapEvent(wid, s"Max rework($newCount) exceeded"))
                } else {
                  updatedWafers += wid -> info.copy(reworkCount = newCount,
                    cdValueHistory = info.cdValueHistory :+ cdValue,
                    classification = Some("FAIL"))
                  reworkWafers :+= wid
                  processRef ! RecordWaferClassified(wid, "FAIL", newCount, cdValue, ignoreReply)
                  publisher(DecisionMade(wid, s"FAIL → Rework (attempt $newCount/$maxRework)", None))
                }

              case "SCRAP" =>
                updatedWafers += wid -> info.copy(cdValueHistory = info.cdValueHistory :+ cdValue,
                  classification = Some("SCRAP"))
                scrapWafers :+= wid
                processRef ! RecordWaferClassified(wid, "SCRAP", 0, cdValue, ignoreReply)
                publisher(DecisionMade(wid, "SCRAP → Terminate", None))
                publisher(ScrapEvent(wid, s"CD=$cdValue nm → SCRAP"))
            }
          }
        }

        val totalPass = updatedWafers.values.count(_.classification.contains("PASS"))
        val totalScrap = updatedWafers.values.count(_.classification.contains("SCRAP"))
        val totalRework = updatedWafers.values.count(w => w.classification.contains("FAIL") && w.reworkCount > 0)

        publisher(LotUpdated(runCtx.scenario.scenarioId, runCtx.scenario.lotSize, totalScrap,
          (1 to runCtx.iteration + 1).map(i => s"Pass-$i").toList, totalPass, totalRework))

        val newCtx = runCtx.copy(wafers = updatedWafers, passCount = totalPass, scrapCount = totalScrap)
        publisher(buildAggregateState(newCtx, reworkActive = reworkWafers.nonEmpty))

        if (reworkWafers.nonEmpty) {
          processRef ! RecordWafersSplitForRework(reworkWafers.toSet, scrapWafers.toSet, runCtx.iteration, ignoreReply)
          publisher(OrchestratorCommand(cmdId(), "DECISION-ENGINE", "SplitLot",
            s"Split ${reworkWafers.size} wafers for rework: ${reworkWafers.mkString(",")}", reworkWafers))
          ctx.self ! RunPhase(newCtx.copy(phase = PhaseSplit(runCtx.foupId, reworkWafers, scrapWafers)))
        } else {
          publisher(OrchestratorCommand(cmdId(), "DECISION-ENGINE", "CompleteLot",
            s"All wafers resolved: $totalPass PASS, $totalScrap SCRAP", Seq.empty))
          ctx.self ! RunPhase(newCtx.copy(phase = PhaseReturnToStocker(runCtx.foupId)))
        }
        Behaviors.same

      // ---- PhaseSplit ----
      case PhaseSplit(foupId, reworkWafers, _) =>
        val sagaId = s"SAGA-SPLIT-${runCtx.iteration}"
        publisher(SagaOperationEvent(sagaId, "SplitLot", "PREPARE",
          runCtx.scenario.scenarioId, s"${runCtx.scenario.scenarioId}-REWORK-$runCtx.iteration", reworkWafers))
        publisher(FoupStateChanged(foupId, "SPLITTING", activeCount(runCtx), reworkWafers.size, "CDSEM",
          lotId = runCtx.scenario.scenarioId,
          reworkLotId = s"${runCtx.scenario.scenarioId}-RWK"))
        publisher(SagaOperationEvent(sagaId, "SplitLot", "COMMITTED",
          runCtx.scenario.scenarioId, s"${runCtx.scenario.scenarioId}-REWORK-$runCtx.iteration", reworkWafers))

        val newIter = runCtx.iteration + 1
        val splitCtx = runCtx.copy(phase = PhaseReworkTransport(foupId), iteration = newIter)
        publisher(buildAggregateState(splitCtx, reworkActive = true))
        ctx.self ! RunPhase(splitCtx)
        Behaviors.same

      // ---- PhaseReworkTransport ----
      case PhaseReworkTransport(foupId) =>
        val scaledMs = scale(runCtx.scenario.amhs.routes("CDSEM" -> "LITHO"), speedMultiplier).toMillis
        val reworkIds = unresolvedIds(runCtx)

        processRef ! RecordTransportStarted(foupId, "CDSEM", "LITHO", scaledMs, ignoreReply)
        publisher(OrchestratorCommand(cmdId(), "AMHS", "TransferFoup",
          s"REWORK: Transport $foupId: CDSEM → LITHO (${scaledMs}ms) [wafers: ${reworkIds.mkString(",")}]", reworkIds))
        publisher(FoupStateChanged(foupId, "IN_TRANSIT", activeCount(runCtx), reworkCount(runCtx), "REWORK_TRANSIT",
          lotId = s"${runCtx.scenario.scenarioId}-RWK"))
        publisher(FoupInTransit(foupId, "CDSEM", "LITHO", scaledMs / 2))
        ctx.pipeToSelf(adapter.sendCommand("AMHS",
          TransferFoup(foupId, "CDSEM", "LITHO"))) {
          case Success(evt) => PhaseResult(runCtx, evt)
          case Failure(err) => PhaseFailed(runCtx, err)
        }
        Behaviors.same

      // ---- PhaseReturnToStocker ----
      case PhaseReturnToStocker(foupId) =>
        val scaledMs = scale(runCtx.scenario.amhs.routes("CDSEM" -> "STOCKER"), speedMultiplier).toMillis

        processRef ! RecordTransportStarted(foupId, "CDSEM", "STOCKER", scaledMs, ignoreReply)
        publisher(OrchestratorCommand(cmdId(), "AMHS", "TransferFoup",
          s"Return $foupId to Stocker (${scaledMs}ms)", Seq.empty))
        publisher(FoupStateChanged(foupId, "RETURNING", 0, 0, "AMHS",
          lotId = runCtx.scenario.scenarioId))
        publisher(FoupInTransit(foupId, "CDSEM", "STOCKER", scaledMs / 2))
        ctx.pipeToSelf(adapter.sendCommand("AMHS",
          TransferFoup(foupId, "CDSEM", "STOCKER"))) {
          case Success(evt) => PhaseResult(runCtx, evt)
          case Failure(err) => PhaseFailed(runCtx, err)
        }
        Behaviors.same

      // ---- PhaseComplete ----
      case PhaseComplete =>
        val s = runCtx.scenario
        val totalRework = runCtx.wafers.values.count(_.reworkCount > 0)

        processRef ! CompleteProcess(s.scenarioId, runCtx.passCount, runCtx.scrapCount, totalRework, ignoreReply)
        publisher(FoupStateChanged(runCtx.foupId, "COMPLETED", 0, 0, "STOCKER",
          lotId = runCtx.scenario.scenarioId))
        publisher(FoupArrivedAtPort(runCtx.foupId, s.stocker.equipmentId, "STOCKER-PORT-1"))
        publisher(LotUpdated(s.scenarioId, s.lotSize, runCtx.scrapCount,
          (1 to runCtx.iteration + 1).map(i => s"Completed-$i").toList,
          runCtx.passCount, totalRework))
        publisher(buildAggregateState(runCtx, reworkActive = false))
        publisher(DemoCompleted(s.scenarioId, s.lotSize, runCtx.passCount, totalRework, runCtx.scrapCount))
        Behaviors.same

      case _ => Behaviors.same
    }
  }

  // ---- Phase Result Handler ----

  private def handlePhaseResult(
    ctx: ActorContext[SimCommand],
    runCtx: RunContext,
    event: EquipmentEvent,
    publisher: FabSimulationEvent => Unit,
    adapter: ActorEquipmentAdapter,
    processRef: EntityRef[FabProcessCommand],
    ignoreReply: ActorRef[ProcessConfirmation],
    speedMultiplier: Double
  ): Behavior[SimCommand] = {
    event match {
      case FoupArrived(foupId, port) =>
        val nextPhase = runCtx.phase match {
          case _: PhaseTransportToLitho => PhaseAtLitho
          case _: PhaseTransportToCdSem => PhaseAtCdSem
          case _: PhaseReworkTransport  => PhaseAtLitho
          case _: PhaseReturnToStocker  => PhaseComplete
          case _                        => runCtx.phase
        }
        ctx.self ! RunPhase(runCtx.copy(phase = nextPhase))
        Behaviors.same

      case JobCompleted(jobId, _, result) =>
        val cdValues: Map[String, Double] = result match {
          case MetrologyResult(_, wafers) =>
            wafers.map { case (wid, cd) => wid -> cd.measuredNm }
          case _ => Map.empty
        }
        val nextPhase = runCtx.phase match {
          case PhaseLithoProcess  => PhaseTransportToCdSem(runCtx.foupId)
          case PhaseCdSemMeasure  => PhaseClassify(cdValues)
          case _                  => runCtx.phase
        }
        ctx.self ! RunPhase(runCtx.copy(phase = nextPhase))
        Behaviors.same

      case _ =>
        ctx.self ! RunPhase(runCtx)
        Behaviors.same
    }
  }

  // ====================================================================
  // Helpers (identical to FabSimulationEngine)
  // ====================================================================

  private def scale(d: FiniteDuration, multiplier: Double): FiniteDuration =
    if (multiplier > 0) (d.toMillis / multiplier).millis else d

  private val rng = new scala.util.Random()
  private def cmdId(): String = UUID.randomUUID().toString.take(8)

  private def activeCount(ctx: RunContext): Int =
    ctx.wafers.values.count(w => w.classification.isEmpty || w.classification.contains("FAIL"))

  private def reworkCount(ctx: RunContext): Int =
    ctx.wafers.values.count(w => w.classification.contains("FAIL") && w.reworkCount > 0)

  private def unresolvedIds(ctx: RunContext): Seq[String] =
    ctx.wafers.values.filter(w => w.classification.isEmpty || w.classification.contains("FAIL"))
      .map(_.waferId).toSeq

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

  private def classifyCd(cdValue: Double, config: net.imadz.fab.scenario.DecisionConfig): String = {
    if (cdValue >= config.lowerSpecNm && cdValue <= config.upperSpecNm) "PASS"
    else if (cdValue > config.upperSpecNm && cdValue <= config.upperSpecNm + config.borderlineWindowNm) "BORDERLINE"
    else if (cdValue > config.upperSpecNm + 8.0) "SCRAP"
    else "FAIL"
  }

  private def phaseStatus(phase: EnginePhase): (String, String) = phase match {
    case PhaseLoad                        => ("LOADING",     "Loading FOUP from Stocker")
    case _: PhaseTransportToLitho         => ("TRANSPORTING","STOCKER → LITHO")
    case PhaseAtLitho                     => ("AT_EQP",      "FOUP at Litho")
    case PhaseLithoProcess                => ("PROCESSING",  "Lithography processing")
    case _: PhaseTransportToCdSem         => ("TRANSPORTING","LITHO → CD-SEM")
    case PhaseAtCdSem                     => ("AT_EQP",      "FOUP at CD-SEM")
    case PhaseCdSemMeasure                => ("MEASURING",   "CD measurement on wafers")
    case _: PhaseClassify                 => ("CLASSIFYING", "Decision Engine classifying")
    case _: PhaseSplit                    => ("SPLITTING",   "Saga split — rework wafers")
    case _: PhaseReworkTransport          => ("REWORKING",   "Rework transport CDSEM→LITHO")
    case _: PhaseReturnToStocker          => ("RETURNING",   "Return FOUP to Stocker")
    case PhaseComplete                    => ("COMPLETED",   "Demo completed")
    case _                                => ("RUNNING",     "Simulation running")
  }

  private def buildAggregateState(ctx: RunContext, reworkActive: Boolean): AggregateStateUpdated = {
    val srcLotId = ctx.scenario.scenarioId
    val rwkLotId = s"$srcLotId-RWK"
    val unresolved = unresolvedIds(ctx)

    val sourceLot = LotStateSnapshot(
      lotId = srcLotId,
      status = if (ctx.phase == PhaseComplete) "Sealed" else "Active",
      waferCount = ctx.scenario.lotSize,
      passCount = ctx.passCount,
      scrapCount = ctx.scrapCount
    )

    val reworkLot = if (reworkActive || ctx.iteration > 0) {
      Some(LotStateSnapshot(
        lotId = rwkLotId,
        status = if (unresolved.isEmpty) "Empty" else "Active",
        waferCount = unresolved.size,
        passCount = 0,
        scrapCount = 0
      ))
    } else None

    val waferSnapshots = ctx.wafers.map { case (wid, info) =>
      val waferLot = info.classification match {
        case Some("FAIL") if info.reworkCount > 0 && reworkActive => rwkLotId
        case _ => srcLotId
      }
      WaferStateSnapshot(
        waferId = wid,
        status = if (info.classification.contains("SCRAP")) "Scrapped" else "Active",
        lotId = waferLot,
        classification = info.classification.getOrElse("Pending"),
        reworkCount = info.reworkCount
      )
    }.toSeq

    AggregateStateUpdated(sourceLot, reworkLot, waferSnapshots)
  }

  // --- Event Sourcing Ledger ---

  @volatile private var ledgerSeq = 0

  private def emitLedgerStep(ctx: RunContext, publisher: FabSimulationEvent => Unit): Unit = {
    val name = ctx.phase match {
      case PhaseLoad                        => "PhaseLoad: Load FOUP from Stocker"
      case _: PhaseTransportToLitho         => "PhaseTransportToLitho: STOCKER → LITHO"
      case PhaseAtLitho                     => "PhaseAtLitho: FOUP arrives at Litho"
      case PhaseLithoProcess                => "PhaseLithoProcess: Run Litho recipe"
      case _: PhaseTransportToCdSem         => "PhaseTransportToCdSem: LITHO → CD-SEM"
      case PhaseAtCdSem                     => "PhaseAtCdSem: FOUP arrives at CD-SEM"
      case PhaseCdSemMeasure                => "PhaseCdSemMeasure: Measure CD on wafers"
      case _: PhaseClassify                 => "PhaseClassify: Decision Engine classifies wafers"
      case _: PhaseSplit                    => "PhaseSplit: Saga SplitLot (TCC Prepare)"
      case _: PhaseReworkTransport          => "PhaseReworkTransport: Rework FOUP CDSEM → LITHO"
      case _: PhaseReturnToStocker          => "PhaseReturnToStocker: Return FOUP to Stocker"
      case PhaseComplete                    => "PhaseComplete: Demo finished, Lot sealed"
    }
    publisher(LedgerStepAdvanced(ledgerSeq, name))
    ledgerSeq += 1
  }
}
