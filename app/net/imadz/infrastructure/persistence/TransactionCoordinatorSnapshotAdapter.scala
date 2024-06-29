package net.imadz.infrastructure.persistence

import akka.persistence.typed.SnapshotAdapter
import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.common.application.saga.TransactionCoordinator
import net.imadz.infrastructure.saga.proto.saga._

import scala.concurrent.ExecutionContext

case class TransactionCoordinatorSnapshotAdapter(repository: CreditBalanceRepository, ec: ExecutionContext) extends SnapshotAdapter[TransactionCoordinator.State] with ParticipantAdapter {

  override def toJournal(state: TransactionCoordinator.State): Any = {
    StatePO(
      currentTransaction = state.currentTransaction.map(transactionToProto),
      completedSteps = state.completedSteps.map(stepToProto).toSeq,
      currentPhase = state.currentPhase,
      currentStep = state.currentStep.map(stepToProto),
      failedSteps = state.failedSteps.map(stepToProto).toSeq,
      compensateSteps = state.compensatedSteps.map(stepToProto).toSeq
    )
  }

  override def fromJournal(from: Any): TransactionCoordinator.State = from match {
    case statePO: StatePO =>
      TransactionCoordinator.State(
        currentTransaction = statePO.currentTransaction.map(protoToTransaction),
        completedSteps = statePO.completedSteps.map(protoToStep).toSet,
        failedSteps = statePO.failedSteps.map(protoToStep).toSet,
        compensatedSteps = statePO.compensateSteps.map(protoToStep).toSet,
        currentPhase = statePO.currentPhase,
        currentStep = statePO.currentStep.map(protoToStep)
      )
    case _ => throw new IllegalArgumentException(s"Unknown journal type: ${from.getClass}")
  }

  private def transactionToProto(transaction: TransactionCoordinator.Transaction): TransactionPO = {
    TransactionPO(
      id = transaction.id,
      steps = transaction.steps.map(stepToProto),
      phases = transaction.phases
    )
  }

  private def stepToProto(step: TransactionCoordinator.TransactionStep): TransactionStepPO = {
    TransactionStepPO(
      id = step.id,
      phase = step.phase,
      // Note: We can't directly serialize the Participant interface.
      // You might need to implement a custom serialization strategy for Participant.
      participant = serializeParticipant(step.participant)
    )
  }

  private def protoToTransaction(proto: TransactionPO): TransactionCoordinator.Transaction = {
    TransactionCoordinator.Transaction(
      id = proto.id,
      steps = proto.steps.map(protoToStep),
      phases = proto.phases
    )
  }

  private def protoToStep(proto: TransactionStepPO): TransactionCoordinator.TransactionStep = {
    TransactionCoordinator.TransactionStep(
      id = proto.id,
      phase = proto.phase,
      participant = deserializeParticipant(proto)
    )
  }

  private def deserializeParticipant(proto: TransactionStepPO): TransactionCoordinator.Participant = {
    // This is a placeholder. You'll need to implement a strategy to deserialize the Participant.
    // This might involve using the 'id' or other fields to reconstruct the appropriate Participant instance.
    deserializeParticipant(proto.participant.get)
  }
}