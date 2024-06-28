package net.imadz.infrastructure.persistence

import akka.persistence.typed.{EventAdapter, EventSeq}
import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.common.application.saga.TransactionCoordinator._
import net.imadz.infrastructure.saga.proto.saga._

import scala.concurrent.ExecutionContext

case class TransactionCoordinatorEventAdapter(repository: CreditBalanceRepository, ec: ExecutionContext) extends EventAdapter[SagaEvent, SagaEventPO.Event] with ParticipantAdapter {

  override def toJournal(e: SagaEvent): SagaEventPO.Event =
    e match {
      case TransactionStarted(transaction) =>
        SagaEventPO.Event.TransactionStarted(TransactionStartedPO(Some(TransactionPO(
          id = transaction.id, steps = transaction.steps.map(
            step => TransactionStepPO(step.id, step.phase, serializeParticipant(step.participant))
          ), phases = transaction.phases
        ))))
      case TransactionPhaseStarted(phase) =>
        SagaEventPO.Event.TransactionPhaseStarted(TransactionPhaseStartedPO(phase))
      case TransactionStepStarted(step) =>
        SagaEventPO.Event.TransactionStepStarted(TransactionStepStartedPO(Some(TransactionStepPO(step.id, step.phase, serializeParticipant(step.participant)))))
      case StepCompleted(step, success) =>
        SagaEventPO.Event.StepCompleted(StepCompletedPO(Some(TransactionStepPO(step.id, step.phase, serializeParticipant(step.participant))), success))
      case PhaseCompleted(phase, success) =>
        SagaEventPO.Event.PhaseCompleted(PhaseCompletedPO(phase, success))
      case TransactionCompleted(success) =>
        SagaEventPO.Event.TransactionCompleted(TransactionCompletedPO(success))

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
        EventSeq.single(TransactionStepStarted(TransactionStep(po.step.get.id, po.step.get.phase, deserializeParticipant(po.step.get.participant.get))))
      case SagaEventPO.Event.StepCompleted(po) =>
        EventSeq.single(StepCompleted(TransactionStep(po.step.get.id, po.step.get.phase, deserializeParticipant(po.step.get.participant.get)), po.success))
      case SagaEventPO.Event.PhaseCompleted(po) =>
        EventSeq.single(PhaseCompleted(po.phase, po.success))
      case SagaEventPO.Event.TransactionCompleted(po) =>
        EventSeq.single(TransactionCompleted(po.success))
      case _ =>
        EventSeq.empty
    }

}