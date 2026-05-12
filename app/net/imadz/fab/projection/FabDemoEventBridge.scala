package net.imadz.fab.projection

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import net.imadz.domain.entities.FabProcessEntity.ProcessEventEnvelope

/**
 * Subscribes to Akka EventStream for [[ProcessEventEnvelope]] published by
 * [[net.imadz.application.aggregates.process.FabProcessAggregate]] and logs them.
 *
 * Full WebSocket bridging from domain events will be added post-MVP via
 * Akka Projection reading from MongoDB journal. For now, the
 * [[net.imadz.fab.orchestration.FabSimulationCoordinator]] publishes all
 * [[FabSimulationEvent]]s directly to the WebSocket hub.
 */
object FabDemoEventBridge {

  sealed trait BridgeCommand
  private case class Wrapped(envelope: ProcessEventEnvelope) extends BridgeCommand

  def apply(): Behavior[BridgeCommand] =
    Behaviors.setup[BridgeCommand] { ctx =>
      val adapter = ctx.messageAdapter[ProcessEventEnvelope](Wrapped)
      ctx.system.eventStream ! akka.actor.typed.eventstream.EventStream.Subscribe(adapter)

      Behaviors.receiveMessage {
        case Wrapped(envelope) =>
          ctx.log.debug(s"[FabDemoEventBridge] Domain event: ${envelope.event.getClass.getSimpleName}")
          Behaviors.same
      }
    }
}
