package net.imadz.infra.saga.persistence.converters

import net.imadz.common.serialization.{PrimitiveConverter, SerializationExtensionImpl}
import net.imadz.infra.saga.SagaPhase._
import net.imadz.infra.saga.SagaTransactionCoordinator._
import net.imadz.infra.saga.proto.saga_v2._
import net.imadz.infra.saga.serialization.SagaExecutorConverter

/**
 * SagaCoordinatorProtoConverters
 * 职责：
 * 1. 定义 SagaTransactionCoordinator 相关事件的 ProtoConverter
 * 2. 混入 SagaStepProtoMapper 以复用 Step 的转换能力
 * 3. 混入 PrimitiveConverter 以获得基础转换能力
 */
trait SagaCoordinatorProtoConverters extends PrimitiveConverter with SagaExecutorConverter {


  // --- Phase Converter (枚举转换) ---
  object PhaseConv extends ProtoConverter[TransactionPhase, TransactionPhasePO] {
    override def toProto(domain: TransactionPhase): TransactionPhasePO = domain match {
      case PreparePhase => TransactionPhasePO.PREPARE_PHASE
      case CommitPhase => TransactionPhasePO.COMMIT_PHASE
      case CompensatePhase => TransactionPhasePO.COMPENSATE_PHASE
    }

    override def fromProto(proto: TransactionPhasePO): TransactionPhase = proto match {
      case TransactionPhasePO.PREPARE_PHASE => PreparePhase
      case TransactionPhasePO.COMMIT_PHASE => CommitPhase
      case TransactionPhasePO.COMPENSATE_PHASE => CompensatePhase
      case _ => PreparePhase // Default or throw
    }
  }

  // --- Event Converters ---

  object TransactionStartedConv extends ProtoConverter[TransactionStarted, TransactionStartedPO] {
    override def toProto(e: TransactionStarted): TransactionStartedPO = TransactionStartedPO(
      transactionId = e.transactionId,
      // [关键] 直接调用 SagaStepProtoMapper 的 toStepPO
      steps = e.steps.map(SagaStepConv.toProto)
    )

    override def fromProto(p: TransactionStartedPO): TransactionStarted = TransactionStarted(
      transactionId = p.transactionId,
      // [关键] 直接调用 SagaStepProtoMapper 的 fromStepPO
      steps = p.steps.map(SagaStepConv.fromProto).toList
    )
  }

  object PhaseSucceededConv extends ProtoConverter[PhaseSucceeded, PhaseSucceededPO] {
    override def toProto(e: PhaseSucceeded): PhaseSucceededPO = PhaseSucceededPO(
      phase = PhaseConv.toProto(e.phase)
    )

    override def fromProto(p: PhaseSucceededPO): PhaseSucceeded = PhaseSucceeded(
      phase = PhaseConv.fromProto(p.phase)
    )
  }

  object PhaseFailedConv extends ProtoConverter[PhaseFailed, PhaseFailedPO] {
    override def toProto(e: PhaseFailed): PhaseFailedPO = PhaseFailedPO(
      phase = PhaseConv.toProto(e.phase)
    )

    override def fromProto(p: PhaseFailedPO): PhaseFailed = PhaseFailed(
      phase = PhaseConv.fromProto(p.phase)
    )
  }

  object TransactionCompletedConv extends ProtoConverter[TransactionCompleted, TransactionCompletedPO] {
    override def toProto(e: TransactionCompleted): TransactionCompletedPO = TransactionCompletedPO(
      transactionId = e.transactionId
    )

    override def fromProto(p: TransactionCompletedPO): TransactionCompleted = TransactionCompleted(
      transactionId = p.transactionId
    )
  }

  object TransactionFailedConv extends ProtoConverter[TransactionFailed, TransactionFailedPO] {
    override def toProto(e: TransactionFailed): TransactionFailedPO = TransactionFailedPO(
      transactionId = e.transactionId,
      reason = e.reason
    )

    override def fromProto(p: TransactionFailedPO): TransactionFailed = TransactionFailed(
      transactionId = p.transactionId,
      reason = p.reason
    )
  }
}