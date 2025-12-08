package net.imadz.infra.saga.serialization

import akka.serialization.Serializer
import net.imadz.common.CommonTypes.iMadzError
import net.imadz.infra.saga.SagaPhase.{CommitPhase, CompensatePhase, PreparePhase}
import net.imadz.infra.saga.proto.saga_v2.{SagaParticipantPO, SagaTransactionStepPO, TransactionPhasePO}
import net.imadz.infra.saga.{SagaParticipant, SagaTransactionStep}

import scala.concurrent.duration.DurationLong
import scala.util.{Failure, Success, Try}

trait AbsSagaTransactionStepSerializer extends Serializer {

  override def includeManifest: Boolean = false

  def identifier: Int

  def toBinary(o: AnyRef): Array[Byte] = o match {
    case step: SagaTransactionStep[_, _] => serializeSagaTransactionStep(step).toByteArray
    case _ => throw new IllegalArgumentException(s"Cannot serialize object of type ${o.getClass}")
  }

  def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    Try(SagaTransactionStepPO.parseFrom(bytes)).map(deserializeSagaTransactionStep) match {
      case Success(step) => step
      case Failure(e) => throw new RuntimeException(s"Failed to deserialize SagaTransactionStep: ${e.getMessage}")
    }
  }

  def serializeSagaTransactionStep(step: SagaTransactionStep[_, _]): SagaTransactionStepPO

  def deserializeSagaTransactionStep(stepPO: SagaTransactionStepPO): SagaTransactionStep[iMadzError, _root_.java.lang.String]

  protected def writeSagaParticipantPO(step: SagaTransactionStep[_, _], genericParticipantPO: SagaParticipantPO): SagaTransactionStepPO = {
    SagaTransactionStepPO(
      stepId = step.stepId,
      phase = step.phase match {
        case PreparePhase => TransactionPhasePO.PREPARE_PHASE
        case CommitPhase => TransactionPhasePO.COMMIT_PHASE
        case CompensatePhase => TransactionPhasePO.COMPENSATE_PHASE
      },
      participant = Some(genericParticipantPO),
      maxRetries = step.maxRetries,
      timeoutDurationMillis = step.timeoutDuration.toMillis,
      retryWhenRecoveredOngoing = step.retryWhenRecoveredOngoing
    )
  }

  protected def readSagaTransactionStep(stepPO: SagaTransactionStepPO, participant: SagaParticipant[iMadzError, String]): SagaTransactionStep[iMadzError, String] = {
    SagaTransactionStep[iMadzError, String](
      stepId = stepPO.stepId,
      phase = stepPO.phase match {
        case TransactionPhasePO.PREPARE_PHASE => PreparePhase
        case TransactionPhasePO.COMMIT_PHASE => CommitPhase
        case TransactionPhasePO.COMPENSATE_PHASE => CompensatePhase
      },
      participant = participant,
      maxRetries = stepPO.maxRetries,
      timeoutDuration = stepPO.timeoutDurationMillis.millis,
      retryWhenRecoveredOngoing = stepPO.retryWhenRecoveredOngoing
    )
  }
}
