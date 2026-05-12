package net.imadz.fab.orchestration

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import net.imadz.fab.events._
import net.imadz.fab.protocol._
import net.imadz.fab.scenario.FabSimulationScenario
import net.imadz.fab.simulation._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
 * Event-driven Fab simulation engine — executes the closed-loop flow:
 *
 *   Stocker (Load) → AMHS → Litho (Process) → AMHS → CD-SEM (Measure) → Classify → Decide
 *
 * Each step sends a command via EquipmentAdapter, awaits the async reply,
 * publishes FabSimulationEvents for the WebSocket frontend, and advances
 * to the next step. Decision (PASS/FAIL/BORDERLINE/SCRAP) happens after CD-SEM.
 */
object FabSimulationEngine {

  sealed trait SimCommand
  case class StartScenario(scenario: FabSimulationScenario, replyTo: ActorRef[SimResult]) extends SimCommand
  case class AdjustSpeed(multiplier: Double) extends SimCommand
  case class InjectFault(equipmentId: String, faultType: String) extends SimCommand
  case object Pause extends SimCommand
  case object Resume extends SimCommand

  case class SimResult(success: Boolean, message: String)

  // Internal messages for async step transitions
  private case class AdvanceTo(scenario: FabSimulationScenario, step: Int) extends SimCommand
  private case class StepCompleted(
    scenario: FabSimulationScenario, step: Int, event: EquipmentEvent
  ) extends SimCommand
  private case class StepFailed(
    scenario: FabSimulationScenario, step: Int, error: Throwable
  ) extends SimCommand

  def apply(
    publisher: FabSimulationEvent => Unit,
    adapter: ActorEquipmentAdapter
  )(implicit ec: ExecutionContext): Behavior[SimCommand] =
    Behaviors.setup { ctx =>
      idle(ctx, publisher, adapter, speedMultiplier = 1.0, running = false)
    }

  // ---- Idle ----
  private def idle(
    ctx: ActorContext[SimCommand],
    publisher: FabSimulationEvent => Unit,
    adapter: ActorEquipmentAdapter,
    speedMultiplier: Double,
    running: Boolean
  ): Behavior[SimCommand] = Behaviors.receiveMessage {
    case StartScenario(scenario, replyTo) if !running =>
      publisher(DemoStarted(scenario.scenarioId, scenario.name, scenario.lotSize, scenario.waferIds))
      spawnSimulators(ctx, scenario, adapter, publisher, speedMultiplier)
      replyTo ! SimResult(success = true, s"Scenario '${scenario.name}' started")
      // Start the flow
      ctx.self ! AdvanceTo(scenario, step = 0)
      idle(ctx, publisher, adapter, speedMultiplier, running = true)

    case StartScenario(_, replyTo) =>
      replyTo ! SimResult(success = false, "A scenario is already running. Reset the page to restart.")
      Behaviors.same

    case AdvanceTo(scenario, step) =>
      runStep(ctx, scenario, step, publisher, adapter, speedMultiplier)

    case StepCompleted(scenario, step, event) =>
      handleStepCompletion(ctx, scenario, step, event, publisher, adapter, speedMultiplier)

    case StepFailed(scenario, step, error) =>
      publisher(ProcessingCompleted("engine", "", success = false, error.getMessage))
      Behaviors.same

    case Pause =>
      Behaviors.same

    case Resume =>
      Behaviors.same

    case _ => Behaviors.same
  }

  // ---- Spawn Simulator Actors ----
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

  // ---- Step Runner ----
  private def runStep(
    ctx: ActorContext[SimCommand],
    scenario: FabSimulationScenario,
    step: Int,
    publisher: FabSimulationEvent => Unit,
    adapter: ActorEquipmentAdapter,
    speedMultiplier: Double
  ): Behavior[SimCommand] = {
    implicit val ec: ExecutionContext = ctx.executionContext
    val scaledProcessing = scale(scenario.litho.processingTime, speedMultiplier)

    step match {
      case 0 =>
        // Load FOUP at Stocker
        publisher(FoupArrivedAtPort(s"FOUP-${scenario.scenarioId}", scenario.stocker.equipmentId, "STOCKER-PORT-1"))
        ctx.self ! AdvanceTo(scenario, step = 1)
        Behaviors.same

      case 1 =>
        // AMHS: Stocker → Litho
        publisher(EquipmentStateChanged(scenario.litho.equipmentId, "LITHO", "Idle", None))
        publisher(FoupInTransit(s"FOUP-${scenario.scenarioId}", "STOCKER", "LITHO",
          scaledProcessing.toMillis / 2))
        ctx.pipeToSelf(adapter.sendCommand("AMHS", TransferFoup(s"FOUP-${scenario.scenarioId}", "STOCKER", "LITHO"))) {
          case Success(evt) => StepCompleted(scenario, step, evt)
          case Failure(err) => StepFailed(scenario, step, err)
        }
        Behaviors.same

      case 2 =>
        // Litho process
        publisher(FoupArrivedAtPort(s"FOUP-${scenario.scenarioId}", scenario.litho.equipmentId, "LITHO-PORT-1"))
        publisher(EquipmentStateChanged(scenario.litho.equipmentId, "LITHO", "Busy", Some("litho-job")))
        publisher(ProcessingStarted(scenario.litho.equipmentId, "LITHO-28-001", scaledProcessing.toMillis))
        ctx.pipeToSelf(adapter.sendCommand(scenario.litho.equipmentId, ProcessRecipe("LITHO-28-001"))) {
          case Success(evt) => StepCompleted(scenario, step, evt)
          case Failure(err) => StepFailed(scenario, step, err)
        }
        Behaviors.same

      case 3 =>
        // AMHS: Litho → CD-SEM
        publisher(ProcessingCompleted(scenario.litho.equipmentId, "litho-job", success = true, ""))
        publisher(EquipmentStateChanged(scenario.litho.equipmentId, "LITHO", "Idle", None))
        publisher(FoupInTransit(s"FOUP-${scenario.scenarioId}", "LITHO", "CDSEM",
          scaledProcessing.toMillis / 2))
        ctx.pipeToSelf(adapter.sendCommand("AMHS", TransferFoup(s"FOUP-${scenario.scenarioId}", "LITHO", "CDSEM"))) {
          case Success(evt) => StepCompleted(scenario, step, evt)
          case Failure(err) => StepFailed(scenario, step, err)
        }
        Behaviors.same

      case 4 =>
        // CD-SEM measure
        publisher(FoupArrivedAtPort(s"FOUP-${scenario.scenarioId}", scenario.cdSem.equipmentId, "CDSEM-PORT-1"))
        publisher(EquipmentStateChanged(scenario.cdSem.equipmentId, "METROLOGY", "Busy", Some("metrology-job")))
        publisher(ProcessingStarted(scenario.cdSem.equipmentId, "CD-MEASURE-001", scaledProcessing.toMillis))
        ctx.pipeToSelf(adapter.sendCommand(scenario.cdSem.equipmentId, ProcessRecipe("CD-MEASURE-001"))) {
          case Success(evt) => StepCompleted(scenario, step, evt)
          case Failure(err) => StepFailed(scenario, step, err)
        }
        Behaviors.same

      case 5 =>
        // Classification + Decision (final step)
        publisher(ProcessingCompleted(scenario.cdSem.equipmentId, "metrology-job", success = true, ""))
        publisher(EquipmentStateChanged(scenario.cdSem.equipmentId, "METROLOGY", "Idle", None))

        // Simulate classification results for each wafer
        val cdSemConfig = scenario.cdSemDetail
        val decisionConfig = scenario.decision
        scenario.waferIds.foreach { wid =>
          val cdValue = generateCdValue(cdSemConfig)
          val cls = classifyCd(cdValue, decisionConfig)
          publisher(MeasurementResultEvent(wid, cdValue, cls, decisionConfig.upperSpecNm))
          publisher(DecisionMade(wid, cls, None))
        }
        publisher(LotUpdated(scenario.scenarioId, scenario.lotSize, 0, List("Litho", "CD-SEM")))
        publisher(DemoCompleted(scenario.scenarioId, scenario.lotSize, 0, 0, 0))
        Behaviors.same

      case _ => Behaviors.same
    }
  }

  // ---- Step Completion Handler (after async reply) ----
  private def handleStepCompletion(
    ctx: ActorContext[SimCommand],
    scenario: FabSimulationScenario,
    step: Int,
    event: EquipmentEvent,
    publisher: FabSimulationEvent => Unit,
    adapter: ActorEquipmentAdapter,
    speedMultiplier: Double
  ): Behavior[SimCommand] = {
    event match {
      case FoupArrived(foupId, port) =>
        // Transport completed → advance
        ctx.self ! AdvanceTo(scenario, step + 1)
        Behaviors.same

      case JobCompleted(jobId, _, result) =>
        // Processing completed → advance
        ctx.self ! AdvanceTo(scenario, step + 1)
        Behaviors.same

      case _ =>
        ctx.self ! AdvanceTo(scenario, step + 1)
        Behaviors.same
    }
  }

  // ---- Simulation Helpers ----
  private def scale(d: FiniteDuration, multiplier: Double): FiniteDuration =
    if (multiplier > 0) (d.toMillis / multiplier).millis else d

  private val rng = new scala.util.Random()

  private def generateCdValue(config: CdSemConfig): Double = {
    val roll = rng.nextDouble()
    val rate = config.passRate + config.borderlineRate + config.failRate + config.scrapRate
    val passEnd = config.passRate / rate
    val bdEnd = passEnd + config.borderlineRate / rate
    val failEnd = bdEnd + config.failRate / rate

    if (roll < passEnd)
      config.targetCdNm + rng.nextGaussian() * config.spreadNm
    else if (roll < bdEnd)
      config.targetCdNm + config.borderlineOffsetNm + rng.nextGaussian() * config.spreadNm * 0.5
    else if (roll < failEnd)
      config.targetCdNm + config.failOffsetNm + rng.nextGaussian() * config.spreadNm * 0.7
    else
      config.targetCdNm * config.scrapFactor + rng.nextGaussian() * config.spreadNm
  }

  private def classifyCd(cdValue: Double, config: net.imadz.fab.scenario.DecisionConfig): String = {
    if (cdValue >= config.lowerSpecNm && cdValue <= config.upperSpecNm) "PASS"
    else if (cdValue > config.upperSpecNm && cdValue <= config.upperSpecNm + config.borderlineWindowNm) "BORDERLINE"
    else if (cdValue > config.upperSpecNm * 1.5) "SCRAP"
    else "FAIL"
  }
}
