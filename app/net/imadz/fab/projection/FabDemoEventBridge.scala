package net.imadz.fab.projection

import akka.actor.typed.Behavior
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.Behaviors
import net.imadz.domain.entities.FabDomainEventEnvelope
import net.imadz.domain.entities.FabProcessEntity._
import net.imadz.fab.events.{DomainEventRecorded, FabSimulationEvent, ProcessEventMapper}

/**
 * Subscribes to Akka Typed EventStream for [[ProcessEventEnvelope]] and
 * [[FabDomainEventEnvelope]] published by FabProcess/Lot/Wafer projections,
 * and bridges domain events to the WebSocket hub.
 *
 * Maintains a per-process [[ProcessEventMapper]] keyed by processId, seeded from
 * [[ProcessStarted]] events. scenarioName defaults to lotId.
 */
object FabDemoEventBridge {

  sealed trait BridgeCommand
  private case class WrappedProcess(envelope: ProcessEventEnvelope) extends BridgeCommand
  private case class WrappedDomain(envelope: FabDomainEventEnvelope) extends BridgeCommand

  def apply(publishToHub: FabSimulationEvent => Unit): Behavior[BridgeCommand] =
    Behaviors.setup[BridgeCommand] { ctx =>
      val processAdapter = ctx.messageAdapter[ProcessEventEnvelope](WrappedProcess)
      val domainAdapter = ctx.messageAdapter[FabDomainEventEnvelope](WrappedDomain)
      ctx.system.eventStream ! EventStream.Subscribe(processAdapter)
      ctx.system.eventStream ! EventStream.Subscribe(domainAdapter)

      var mappers = Map.empty[String, ProcessEventMapper]

      Behaviors.receiveMessage {
        case WrappedProcess(ProcessEventEnvelope(processId, event)) =>
          emitDomainEvent(publishToHub, event)
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

        case WrappedDomain(FabDomainEventEnvelope(aggType, aggId, event)) =>
          emitDomainEvent(publishToHub, event)
          Behaviors.same
      }
    }

  private def emitDomainEvent(publishToHub: FabSimulationEvent => Unit, event: Any): Unit = {
    val now = System.currentTimeMillis()
    publishToHub(DomainEventRecorded(
      eventType = event.getClass.getSimpleName,
      data = event.toString,
      timestamp = now
    ))
  }
}
