package net.imadz.fab.simulation

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import net.imadz.fab.protocol._
import scala.concurrent.duration.FiniteDuration

/**
 * Stocker (FOUP buffer/storage) simulator.
 *
 * Manages FOUP loading/unloading at ports. A Stocker has N ports;
 * each port can hold one FOUP at a time.
 */
class StockerSimulator {

  case class StockerState(
    equipmentId: String,
    ports: Map[String, Option[String]], // portId → Some(foupId) or None(available)
    loadTime: FiniteDuration,
    speedMultiplier: Double = 1.0
  )

  def apply(config: StockerConfig): Behavior[SimulatorCommand] =
    Behaviors.setup { _ =>
      val ports = (1 to config.portCount).map(i => s"STOCKER-PORT-$i" -> Option.empty[String]).toMap
      running(StockerState(config.equipmentId, ports, config.loadTime))
    }

  private def running(state: StockerState): Behavior[SimulatorCommand] = Behaviors.receiveMessage {
    case SimulateCommand(cmd: LoadFoup, replyTo) =>
      // Assign to first available port
      state.ports.find(_._2.isEmpty) match {
        case Some((portId, _)) =>
          val newPorts = state.ports + (portId -> Some(cmd.foupId))
          replyTo ! FoupArrived(cmd.foupId, portId)
          running(state.copy(ports = newPorts))
        case None =>
          replyTo ! StatusReport(state.equipmentId, Error, None,
            state.ports.map { case (k, v) => k -> v })
          Behaviors.same
      }

    case SimulateCommand(cmd: UnloadFoup, replyTo) =>
      state.ports.find(_._2.contains(cmd.foupId)) match {
        case Some((portId, _)) =>
          val newPorts = state.ports + (portId -> None)
          replyTo ! FoupDeparted(cmd.foupId, portId)
          running(state.copy(ports = newPorts))
        case None =>
          replyTo ! StatusReport(state.equipmentId, Error, None,
            state.ports.map { case (k, v) => k -> v })
          Behaviors.same
      }

    case SimulateCommand(QueryStatus(), replyTo) =>
      replyTo ! StatusReport(state.equipmentId, Idle, None,
        state.ports.map { case (k, v) => k -> v })
      Behaviors.same

    case _ => Behaviors.same
  }
}
