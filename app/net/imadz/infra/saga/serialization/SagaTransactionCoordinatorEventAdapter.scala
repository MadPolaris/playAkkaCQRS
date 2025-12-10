package net.imadz.infra.saga.serialization

import akka.actor.typed.ActorSystem
import akka.persistence.typed.{EventAdapter, EventSeq}
import net.imadz.infra.saga.SagaPhase.{CommitPhase, CompensatePhase, PreparePhase}
import net.imadz.infra.saga.proto.saga_v2._
import net.imadz.infra.saga.{ForSaga, SagaPhase, SagaTransactionCoordinator}

import scala.concurrent.ExecutionContext

case class SagaTransactionCoordinatorEventAdapter(system: ActorSystem[Nothing], stepSerializer:AbsSagaTransactionStepSerializer, ec: ExecutionContext)
  extends EventAdapter[SagaTransactionCoordinator.Event, SagaTransactionCoordinatorEventPO.Event]
  with ForSaga {
  override def toJournal(e: SagaTransactionCoordinator.Event): SagaTransactionCoordinatorEventPO.Event = e match {
    case SagaTransactionCoordinator.TransactionStarted(transactionId, steps) => SagaTransactionCoordinatorEventPO.Event.Started(TransactionStartedPO(transactionId = transactionId, steps = steps.map(stepSerializer.serializeSagaTransactionStep)))
    case SagaTransactionCoordinator.PhaseSucceeded(phase) => SagaTransactionCoordinatorEventPO.Event.PhaseSucceeded(PhaseSucceededPO(serializePhase(phase)))
    case SagaTransactionCoordinator.PhaseFailed(phase) => SagaTransactionCoordinatorEventPO.Event.PhaseFailed(PhaseFailedPO(serializePhase(phase)))
    case SagaTransactionCoordinator.TransactionCompleted(transactionId) => SagaTransactionCoordinatorEventPO.Event.TransactionCompleted(TransactionCompletedPO(transactionId))
    case SagaTransactionCoordinator.TransactionFailed(transactionId, reason) => SagaTransactionCoordinatorEventPO.Event.TransactionFailed(TransactionFailedPO(transactionId, reason))
  }


  private def serializePhase(phase: SagaPhase.TransactionPhase) = {
    phase match {
      case PreparePhase => TransactionPhasePO.PREPARE_PHASE
      case CommitPhase => TransactionPhasePO.COMMIT_PHASE
      case CompensatePhase => TransactionPhasePO.COMPENSATE_PHASE
    }
  }

  private def deserializePhase(phase: TransactionPhasePO) = phase match {
    case TransactionPhasePO.PREPARE_PHASE => SagaPhase.PreparePhase
    case TransactionPhasePO.COMMIT_PHASE => SagaPhase.CommitPhase
    case TransactionPhasePO.COMPENSATE_PHASE => SagaPhase.CompensatePhase
    case TransactionPhasePO.Unrecognized(_) =>
      throw new IllegalArgumentException(s"Unrecognized transaction phase: $phase")
  }

  override def manifest(event: SagaTransactionCoordinator.Event): String = event.getClass.getName

  override def fromJournal(p: SagaTransactionCoordinatorEventPO.Event, manifest: String): EventSeq[SagaTransactionCoordinator.Event] = {
    val event = p match {
      case SagaTransactionCoordinatorEventPO.Event.Started(TransactionStartedPO(transactionId, steps, _)) =>
        SagaTransactionCoordinator.TransactionStarted(
          transactionId,
          steps.map(stepSerializer.deserializeSagaTransactionStep).toList
        )
      case SagaTransactionCoordinatorEventPO.Event.PhaseSucceeded(PhaseSucceededPO(phase, _)) =>
        SagaTransactionCoordinator.PhaseSucceeded(deserializePhase(phase))
      case SagaTransactionCoordinatorEventPO.Event.PhaseFailed(PhaseFailedPO(phase, _)) =>
        SagaTransactionCoordinator.PhaseFailed(deserializePhase(phase))
      case SagaTransactionCoordinatorEventPO.Event.TransactionCompleted(TransactionCompletedPO(transactionId, _)) =>
        SagaTransactionCoordinator.TransactionCompleted(transactionId)
      case SagaTransactionCoordinatorEventPO.Event.TransactionFailed(TransactionFailedPO(transactionId, reason, _)) =>
        SagaTransactionCoordinator.TransactionFailed(transactionId, reason)
      case _ =>
        throw new IllegalArgumentException(s"Unrecognized event: $p")
    }
    EventSeq.single(event)
  }
}
