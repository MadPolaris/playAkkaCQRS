package net.imadz.infra.saga.persistence

import akka.actor.ExtendedActorSystem
import akka.persistence.typed.{EventAdapter, EventSeq}
import net.imadz.common.serialization.SerializationExtension
import net.imadz.infra.saga.SagaTransactionCoordinator
import net.imadz.infra.saga.persistence.converters.SagaCoordinatorProtoConverters
import net.imadz.infra.saga.proto.saga_v2._

import scala.concurrent.ExecutionContext

/**
 * SagaTransactionCoordinatorEventAdapter
 *
 * Implements a design with separated converters:
 * 1. Mixes in SagaCoordinatorProtoConverters to get all conversion logic
 * 2. Injects ExtendedActorSystem to obtain SerializationExtension
 */
class SagaTransactionCoordinatorEventAdapter(override val system: ExtendedActorSystem)
  extends EventAdapter[SagaTransactionCoordinator.Event, SagaTransactionCoordinatorEventPO]
    with SagaCoordinatorProtoConverters {

  // If ExecutionContext is needed inside Mapper (not currently required, but kept just in case)
  implicit val ec: ExecutionContext = system.dispatcher

  override def manifest(event: SagaTransactionCoordinator.Event): String = ""

  override def toJournal(e: SagaTransactionCoordinator.Event): SagaTransactionCoordinatorEventPO = {
    val payload = e match {
      case evt: SagaTransactionCoordinator.TransactionStarted =>
        SagaTransactionCoordinatorEventPO.Event.Started(TransactionStartedConv.toProto(evt))

      case evt: SagaTransactionCoordinator.PhaseSucceeded =>
        SagaTransactionCoordinatorEventPO.Event.PhaseSucceeded(PhaseSucceededConv.toProto(evt))

      case evt: SagaTransactionCoordinator.PhaseFailed =>
        SagaTransactionCoordinatorEventPO.Event.PhaseFailed(PhaseFailedConv.toProto(evt))

      case evt: SagaTransactionCoordinator.TransactionCompleted =>
        SagaTransactionCoordinatorEventPO.Event.TransactionCompleted(TransactionCompletedConv.toProto(evt))

      case evt: SagaTransactionCoordinator.TransactionFailed =>
        SagaTransactionCoordinatorEventPO.Event.TransactionFailed(TransactionFailedConv.toProto(evt))

      case evt: SagaTransactionCoordinator.TransactionSuspended =>
        SagaTransactionCoordinatorEventPO.Event.Suspended(TransactionSuspendedConv.toProto(evt))

      case evt: SagaTransactionCoordinator.TransactionPaused =>
        SagaTransactionCoordinatorEventPO.Event.Paused(TransactionPausedConv.toProto(evt))

      case evt: SagaTransactionCoordinator.TransactionResumed =>
        SagaTransactionCoordinatorEventPO.Event.Resumed(TransactionResumedConv.toProto(evt))

      case evt: SagaTransactionCoordinator.TransactionResolved =>
        SagaTransactionCoordinatorEventPO.Event.Resolved(TransactionResolvedConv.toProto(evt))

      case evt: SagaTransactionCoordinator.StepGroupSucceeded =>
        SagaTransactionCoordinatorEventPO.Event.StepGroupSucceeded(StepGroupSucceededConv.toProto(evt))

      case evt: SagaTransactionCoordinator.TransactionRetried =>
        SagaTransactionCoordinatorEventPO.Event.Retried(TransactionRetriedConv.toProto(evt))

      case evt: SagaTransactionCoordinator.StepGroupStarted =>
        SagaTransactionCoordinatorEventPO.Event.StepGroupStarted(StepGroupStartedConv.toProto(evt))

      case evt: SagaTransactionCoordinator.StepResultReceived =>
        SagaTransactionCoordinatorEventPO.Event.StepResultReceived(StepResultReceivedConv.toProto(evt))
    }
    SagaTransactionCoordinatorEventPO(payload)
  }

  override def fromJournal(p: SagaTransactionCoordinatorEventPO, manifest: String): EventSeq[SagaTransactionCoordinator.Event] = {
    p.event match {
      case SagaTransactionCoordinatorEventPO.Event.Started(po) =>
        EventSeq.single(TransactionStartedConv.fromProto(po))

      case SagaTransactionCoordinatorEventPO.Event.PhaseSucceeded(po) =>
        EventSeq.single(PhaseSucceededConv.fromProto(po))

      case SagaTransactionCoordinatorEventPO.Event.PhaseFailed(po) =>
        EventSeq.single(PhaseFailedConv.fromProto(po))

      case SagaTransactionCoordinatorEventPO.Event.TransactionCompleted(po) =>
        EventSeq.single(TransactionCompletedConv.fromProto(po))

      case SagaTransactionCoordinatorEventPO.Event.TransactionFailed(po) =>
        EventSeq.single(TransactionFailedConv.fromProto(po))

      case SagaTransactionCoordinatorEventPO.Event.Suspended(po) =>
        EventSeq.single(TransactionSuspendedConv.fromProto(po))

      case SagaTransactionCoordinatorEventPO.Event.Paused(po) =>
        EventSeq.single(TransactionPausedConv.fromProto(po))

      case SagaTransactionCoordinatorEventPO.Event.Resumed(po) =>
        EventSeq.single(TransactionResumedConv.fromProto(po))

      case SagaTransactionCoordinatorEventPO.Event.Resolved(po) =>
        EventSeq.single(TransactionResolvedConv.fromProto(po))

      case SagaTransactionCoordinatorEventPO.Event.StepGroupSucceeded(po) =>
        EventSeq.single(StepGroupSucceededConv.fromProto(po))

      case SagaTransactionCoordinatorEventPO.Event.Retried(po) =>
        EventSeq.single(TransactionRetriedConv.fromProto(po))

      case SagaTransactionCoordinatorEventPO.Event.StepGroupStarted(po) =>
        EventSeq.single(StepGroupStartedConv.fromProto(po))

      case SagaTransactionCoordinatorEventPO.Event.StepResultReceived(po) =>
        EventSeq.single(StepResultReceivedConv.fromProto(po))

      case _ =>
        EventSeq.empty
    }
  }

}
