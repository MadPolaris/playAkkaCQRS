package net.imadz.fab.projection

import akka.actor.typed.Behavior
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.Behaviors
import net.imadz.application.events.SagaProgressEvent
import net.imadz.domain.entities.FabDomainEventEnvelope
import net.imadz.domain.entities.FabProcessEntity._
import net.imadz.fab.events.{DomainEventRecorded, FabSimulationEvent, ProcessEventMapper}

/**
 * Subscribes to Akka Typed EventStream for [[ProcessEventEnvelope]],
 * [[FabDomainEventEnvelope]], and [[SagaProgressEvent]] published by
 * FabProcess/Lot/Wafer/Saga projections and the SagaTransactionCoordinator,
 * and bridges domain events to the WebSocket hub.
 *
 * Domain events are classified into 4 logical layers:
 *   Layer 0: Chain / Orchestration (ChainStarted, ChainCompleted, ChainFailed)
 *   Layer 1: Saga / Transaction      (TransactionStarted, StepOngoing, StepCompleted, ...)
 *   Layer 2: Aggregate / Entity      (LotCreated, WaferTransferCommitted, LotSealed, ...)
 *   Layer 3: Process / Execution     (ProcessStarted, FoupLoaded, TransportStarted, ...)
 */
object FabDemoEventBridge {

  sealed trait BridgeCommand
  private case class WrappedProcess(envelope: ProcessEventEnvelope) extends BridgeCommand
  private case class WrappedDomain(envelope: FabDomainEventEnvelope) extends BridgeCommand
  private case class WrappedSaga(event: SagaProgressEvent) extends BridgeCommand

  def apply(publishToHub: FabSimulationEvent => Unit): Behavior[BridgeCommand] =
    Behaviors.setup[BridgeCommand] { ctx =>
      val processAdapter = ctx.messageAdapter[ProcessEventEnvelope](WrappedProcess)
      val domainAdapter = ctx.messageAdapter[FabDomainEventEnvelope](WrappedDomain)
      val sagaAdapter = ctx.messageAdapter[SagaProgressEvent](WrappedSaga)
      ctx.system.eventStream ! EventStream.Subscribe(processAdapter)
      ctx.system.eventStream ! EventStream.Subscribe(domainAdapter)
      ctx.system.eventStream ! EventStream.Subscribe(sagaAdapter)

      var mappers = Map.empty[String, ProcessEventMapper]

      Behaviors.receiveMessage {
        case WrappedProcess(ProcessEventEnvelope(processId, event)) =>
          emitDomainEvent(publishToHub, event, aggregateType = "FabProcess",
            aggregateId = processId, layer = 3)
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
          val layer = aggType match {
            case "Chain" => 0
            case "FabSagaTransaction" => 2
            case _ => 2 // Lot, Wafer → Aggregate layer
          }
          emitDomainEvent(publishToHub, event, aggregateType = aggType,
            aggregateId = aggId.toString, layer = layer)
          Behaviors.same

        case WrappedSaga(event) =>
          emitDomainEvent(publishToHub, event, aggregateType = "Saga",
            aggregateId = event.traceId, layer = 1)
          Behaviors.same
      }
    }

  private def emitDomainEvent(
    publishToHub: FabSimulationEvent => Unit,
    event: Any,
    aggregateType: String,
    aggregateId: String,
    layer: Int
  ): Unit = {
    val now = System.currentTimeMillis()
    publishToHub(DomainEventRecorded(
      eventType = event.getClass.getSimpleName,
      aggregateType = aggregateType,
      aggregateId = aggregateId,
      data = event.toString,
      timestamp = now,
      layer = layer
    ))
  }
}
