package net.imadz.fab.orchestration

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import net.imadz.common.CommonTypes.Id
import net.imadz.common.Id
import net.imadz.fab.events._
import net.imadz.fab.protocol._
import net.imadz.fab.scenario.FabSimulationScenario
import net.imadz.fab.simulation._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
 * Event-driven Fab simulation engine.
 *
 * Replaces the synchronous recursion in the old FabFlowEngine with an
 * Akka Typed Actor that:
 *   1. Sends commands to equipment simulators via EquipmentAdapter
 *   2. Receives async equipment events
 *   3. Delegates decision-making to DynamicFlowAssembler
 *   4. Publishes FabSimulationEvents to EventStream for the WebSocket bridge
 *
 * Flow: Start → Load → Litho → Measure → Classify → Decide → (Rework | Advance | Scrap)
 */
object FabSimulationEngine {

  sealed trait SimCommand
  case class StartScenario(scenario: FabSimulationScenario, replyTo: ActorRef[SimResult]) extends SimCommand
  case class AdjustSpeed(multiplier: Double) extends SimCommand
  case class InjectFault(equipmentId: String, faultType: String) extends SimCommand
  case object Pause extends SimCommand
  case object Resume extends SimCommand
  case object GetStatus extends SimCommand

  case class SimResult(success: Boolean, message: String)

  // Internal messages for step transitions
  private case class ContinueWith(scenario: FabSimulationScenario, step: Int) extends SimCommand
  private case class EquipmentReply(scenario: FabSimulationScenario, step: Int, event: EquipmentEvent) extends SimCommand
  private case object PublishTick extends SimCommand

  def apply(
    publisher: FabSimulationEvent => Unit,
    adapter: ActorEquipmentAdapter
  )(implicit ec: ExecutionContext): Behavior[SimCommand] =
    Behaviors.setup { ctx =>
      idle(ctx, publisher, adapter, speedMultiplier = 1.0)
    }

  private def idle(
    ctx: ActorContext[SimCommand],
    publisher: FabSimulationEvent => Unit,
    adapter: ActorEquipmentAdapter,
    speedMultiplier: Double
  ): Behavior[SimCommand] = Behaviors.receiveMessage {
    case StartScenario(scenario, replyTo) =>
      publisher(DemoStarted(scenario.scenarioId, scenario.name, scenario.lotSize, scenario.waferIds))
      // Create litho and CD-SEM simulators
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
      replyTo ! SimResult(success = true, s"Scenario '${scenario.name}' started")
      ctx.self ! ContinueWith(scenario, step = 0)
      Behaviors.same

    case _ => Behaviors.same
  }

  // Step 0: Load FOUP at Stocker
  // Step 1: Transport to Litho
  // Step 2: Process Litho
  // Step 3: Transport to CD-SEM
  // Step 4: Measure CD-SEM
  // Step 5: Complete
}

// Minimal stub for compilation — full flow logic will be implemented in the next iteration.
