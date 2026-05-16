package net.imadz.fab.projection

import akka.actor.typed.Behavior
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.Behaviors
import net.imadz.application.events.SagaProgressEvent
import net.imadz.domain.entities.FabDomainEventEnvelope
import net.imadz.fab.events.{DomainEventRecorded, FabSimulationEvent}

/**
 * Subscribes to Akka Typed EventStream for [[FabDomainEventEnvelope]]
 * and [[SagaProgressEvent]] published by FabProcess/Lot/Wafer/Saga
 * projection handlers, the SagaTransactionCoordinator, and FabChainExecutor,
 * and bridges domain events to the WebSocket hub.
 *
 * Domain events are classified into 4 logical layers:
 *   Layer 0: Chain / Orchestration (WorkOrderAccepted, WorkOrderCompleted, WorkOrderFailed)
 *   Layer 1: Saga / Transaction      (TransactionStarted, StepOngoing, StepCompleted, ...)
 *   Layer 2: Aggregate / Entity      (LotCreated, WaferTransferCommitted, LotSealed, ...)
 *   Layer 3: Process / Execution     (ProcessStarted, FoupLoaded, TransportStarted, ...)
 */
object FabDemoEventBridge {

  sealed trait BridgeCommand
  private case class WrappedDomain(envelope: FabDomainEventEnvelope) extends BridgeCommand
  private case class WrappedSaga(event: SagaProgressEvent) extends BridgeCommand

  def apply(publishToHub: FabSimulationEvent => Unit): Behavior[BridgeCommand] =
    Behaviors.setup[BridgeCommand] { ctx =>
      val domainAdapter = ctx.messageAdapter[FabDomainEventEnvelope](WrappedDomain)
      val sagaAdapter = ctx.messageAdapter[SagaProgressEvent](WrappedSaga)
      ctx.system.eventStream ! EventStream.Subscribe(domainAdapter)
      ctx.system.eventStream ! EventStream.Subscribe(sagaAdapter)

      Behaviors.receiveMessage {
        case WrappedDomain(FabDomainEventEnvelope(aggType, aggId, event)) =>
          val layer = aggType match {
            case "FabChain"           => 0
            case "WorkOrder"          => 0
            case "FabSagaTransaction" => 1
            case "FabProcess"         => 3
            case _                    => 2 // Lot, Wafer
          }
          emitDomainEvent(publishToHub, event, aggregateType = aggType,
            aggregateId = aggId, layer = layer)
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
