package net.imadz.fab.projection

import akka.actor.typed.Behavior
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.Behaviors
import net.imadz.domain.entities.FabProcessEntity._
import net.imadz.fab.events.{DomainEventRecorded, FabSimulationEvent, ProcessEventMapper}

/**
 * Subscribes to Akka Typed EventStream for [[ProcessEventEnvelope]] published by
 * [[net.imadz.application.projection.FabProcessProjectionHandler]] and bridges
 * domain events to the WebSocket hub.
 *
 * When MongoDB + MySQL are available, the Akka Projection reads domain events
 * from the journal and publishes to EventStream, where this bridge picks them up.
 * When databases are unavailable, the Coordinator publishes simulation events
 * directly to the WebSocket hub as a fallback.
 *
 * Maintains a per-process [[ProcessEventMapper]] keyed by processId, seeded from
 * [[ProcessStarted]] events. scenarioName defaults to lotId.
 */
object FabDemoEventBridge {

  sealed trait BridgeCommand
  private case class Wrapped(envelope: ProcessEventEnvelope) extends BridgeCommand

  def apply(publishToHub: FabSimulationEvent => Unit): Behavior[BridgeCommand] =
    Behaviors.setup[BridgeCommand] { ctx =>
      val adapter = ctx.messageAdapter[ProcessEventEnvelope](Wrapped)
      ctx.system.eventStream ! EventStream.Subscribe(adapter)

      var mappers = Map.empty[String, ProcessEventMapper]

      Behaviors.receiveMessage {
        case Wrapped(ProcessEventEnvelope(processId, event)) =>
          // Emit raw domain event to sidebar audit trail
          val now = System.currentTimeMillis()
          publishToHub(DomainEventRecorded(
            eventType = event.getClass.getSimpleName,
            data = event.toString,
            timestamp = now
          ))

          event match {
            case ProcessStarted(lotId, waferIds, lotSize) =>
              val mapper = new ProcessEventMapper(lotId, lotId, waferIds.toSeq, lotSize)
              mappers = mappers + (processId -> mapper)
              mapper.mapToFabSimulationEvent(event).foreach(publishToHub)

            case _ =>
              mappers.get(processId).foreach { mapper =>
                mapper.mapToFabSimulationEvent(event).foreach(publishToHub)
              }
          }
          Behaviors.same
      }
    }
}
