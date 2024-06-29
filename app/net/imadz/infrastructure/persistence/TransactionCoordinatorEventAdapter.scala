package net.imadz.infrastructure.persistence

import akka.persistence.typed.{EventAdapter, EventSeq}
import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.common.application.saga.TransactionCoordinator.{StepStatus, _}
import net.imadz.infrastructure.saga.proto.saga.StepStatusPO.{STEP_COMPENSATED, STEP_COMPLETED, STEP_CREATED, STEP_FAILED, STEP_ONGOING, STEP_TIMEOUT}
import net.imadz.infrastructure.saga.proto.saga._

import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

case class TransactionCoordinatorEventAdapter(repository: CreditBalanceRepository, ec: ExecutionContext) extends EventAdapter[SagaEvent, SagaEventPO.Event] with ParticipantAdapter {

  override def toJournal(e: SagaEvent): SagaEventPO.Event =
    e match {
      case TransactionStarted(transaction) =>
        SagaEventPO.Event.TransactionStarted(TransactionStartedPO(Some(TransactionPO(
          id = transaction.id,
          steps = transaction.steps.map(serializeTransactionStep),
          phases = transaction.phases
        ))))
      case TransactionPhaseStarted(phase) =>
        SagaEventPO.Event.TransactionPhaseStarted(TransactionPhaseStartedPO(phase))
      case TransactionStepStarted(step) =>
        SagaEventPO.Event.TransactionStepStarted(TransactionStepStartedPO(Some(serializeTransactionStep(step))))
      case StepCompleted(step, success) =>
        SagaEventPO.Event.StepCompleted(StepCompletedPO(Some(serializeTransactionStep(step)), success))
      case PhaseCompleted(phase, success) =>
        SagaEventPO.Event.PhaseCompleted(PhaseCompletedPO(phase, success))
      case TransactionCompleted(success) =>
        SagaEventPO.Event.TransactionCompleted(TransactionCompletedPO(success))

    }

  private def serializeTransactionStep(step: TransactionStep): TransactionStepPO = {
    TransactionStepPO(step.id, step.phase,
      serializeParticipant(step.participant),
      serializeStepStatus(step.status),
      step.failedReason, step.retries, step.timeoutDuration.length.toInt)
  }

  private def deserializeTransactionStepPO(stepPO: TransactionStepPO): TransactionStep =
    TransactionStep(stepPO.id, stepPO.phase,
      deserializeParticipant(stepPO.getParticipant), deserializeStepStatus(stepPO.status),
      stepPO.failedReason, stepPO.retries, FiniteDuration(stepPO.timeoutDuration, TimeUnit.SECONDS)
    )

  private def serializeStepStatus(status: StepStatus): StepStatusPO = status match {
    case StepCreated => StepStatusPO.STEP_CREATED
    case StepOngoing => StepStatusPO.STEP_ONGOING
    case StepCompleted => StepStatusPO.STEP_COMPLETED
    case StepFailed => StepStatusPO.STEP_FAILED
  }

  private def deserializeStepStatus(stepStatusPO: StepStatusPO): StepStatus = stepStatusPO match {
    case STEP_CREATED => StepCreated
    case STEP_ONGOING => StepOngoing
    case STEP_COMPLETED => StepCompleted
    case STEP_FAILED => StepFailed
    case STEP_TIMEOUT => StepTimedOut
    case STEP_COMPENSATED => StepCompensated
  }

  override def manifest(event: SagaEvent): String = event.getClass.getName

  override def fromJournal(p: SagaEventPO.Event, manifest: String): EventSeq[SagaEvent] =
    p match {
      case SagaEventPO.Event.TransactionStarted(po) =>
        EventSeq.single(TransactionStarted(Transaction(
          id = po.transaction.get.id,
          steps = po.transaction.get.steps.map(step => TransactionStep(step.id, step.phase, deserializeParticipant(step.participant.get))),
          phases = po.transaction.get.phases
        )))
      case SagaEventPO.Event.TransactionPhaseStarted(po) =>
        EventSeq.single(TransactionPhaseStarted(po.phase))
      case SagaEventPO.Event.TransactionStepStarted(po) =>
        EventSeq.single(TransactionStepStarted(deserializeTransactionStepPO(po.step.get)))
      case SagaEventPO.Event.StepCompleted(po) =>
        EventSeq.single(StepCompleted(deserializeTransactionStepPO(po.step.get), po.success))
      case SagaEventPO.Event.PhaseCompleted(po) =>
        EventSeq.single(PhaseCompleted(po.phase, po.success))
      case SagaEventPO.Event.TransactionCompleted(po) =>
        EventSeq.single(TransactionCompleted(po.success))
      case _ =>
        EventSeq.empty
    }

}