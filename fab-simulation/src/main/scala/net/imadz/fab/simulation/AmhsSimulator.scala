package net.imadz.fab.simulation

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import net.imadz.fab.protocol._
import scala.concurrent.duration._

/**
 * Automated Material Handling System (AMHS) simulator.
 *
 * Models FOUP transport between equipment areas. Single-rail model:
 * only one transport active at a time. Route times configured in AmhsConfig.
 */
class AmhsSimulator {

  def apply(config: AmhsConfig, speedMultiplier: Double = 1.0): Behavior[SimulatorCommand] =
    Behaviors.withTimers { timers =>
      idle(config, speedMultiplier, timers)
    }

  private def idle(
    config: AmhsConfig, speedMultiplier: Double,
    timers: akka.actor.typed.scaladsl.TimerScheduler[SimulatorCommand]
  ): Behavior[SimulatorCommand] = Behaviors.receiveMessage {
    case SimulateCommand(cmd: TransferFoup, replyTo) =>
      val key = (cmd.fromPort, cmd.toPort)
      config.routes.get(key) match {
        case Some(duration) =>
          val scaled = scale(duration, speedMultiplier)
          // Reply only after transport completes (TimerTick → FoupArrived).
          // Immediate FoupDeparted would complete the ask prematurely and
          // cause the engine to re-enter the transport phase forever.
          timers.startSingleTimer(TimerTick, scaled)
          inTransit(cmd.foupId, cmd.fromPort, cmd.toPort, replyTo, config, speedMultiplier, timers)

        case None =>
          replyTo ! JobFailed("", "AMHS", "NO_ROUTE",
            s"No route from ${cmd.fromPort} to ${cmd.toPort}")
          Behaviors.same
      }

    case SimulateCommand(QueryStatus(), replyTo) =>
      replyTo ! StatusReport("AMHS", Idle, None, Map.empty)
      Behaviors.same

    case _ => Behaviors.same
  }

  private def inTransit(
    foupId: String, fromPort: String, toPort: String,
    caller: akka.actor.typed.ActorRef[EquipmentEvent],
    config: AmhsConfig, speedMultiplier: Double,
    timers: akka.actor.typed.scaladsl.TimerScheduler[SimulatorCommand]
  ): Behavior[SimulatorCommand] = Behaviors.receiveMessage {
    case TimerTick =>
      caller ! FoupArrived(foupId, toPort)
      idle(config, speedMultiplier, timers)

    case SimulateCommand(QueryStatus(), replyTo) =>
      replyTo ! StatusReport("AMHS", Busy, Some(foupId), Map.empty)
      Behaviors.same

    case _ => Behaviors.same
  }

  private def scale(d: FiniteDuration, multiplier: Double): FiniteDuration =
    if (multiplier > 0) (d.toMillis / multiplier).millis else d
}
