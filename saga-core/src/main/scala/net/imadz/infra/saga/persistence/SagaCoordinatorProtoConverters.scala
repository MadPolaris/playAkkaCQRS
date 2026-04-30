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
      steps = e.steps.map(SagaStepConv.toProto),
      traceId = e.traceId,
      singleStep = e.singleStep
    )

    override def fromProto(p: TransactionStartedPO): TransactionStarted = TransactionStarted(
      transactionId = p.transactionId,
      steps = p.steps.map(SagaStepConv.fromProto).toList,
      traceId = p.traceId,
      singleStep = p.singleStep
    )
  }

  object TransactionPausedConv extends ProtoConverter[TransactionPaused, TransactionPausedPO] {
    override def toProto(e: TransactionPaused): TransactionPausedPO = TransactionPausedPO(
      transactionId = e.transactionId,
      traceId = e.traceId
    )

    override def fromProto(p: TransactionPausedPO): TransactionPaused = TransactionPaused(
      transactionId = p.transactionId,
      traceId = p.traceId
    )
  }

  object TransactionResumedConv extends ProtoConverter[TransactionResumed, TransactionResumedPO] {
    override def toProto(e: TransactionResumed): TransactionResumedPO = TransactionResumedPO(
      transactionId = e.transactionId,
      traceId = e.traceId
    )

    override def fromProto(p: TransactionResumedPO): TransactionResumed = TransactionResumed(
      transactionId = p.transactionId,
      traceId = p.traceId
    )
  }

  object PhaseSucceededConv extends ProtoConverter[PhaseSucceeded, PhaseSucceededPO] {
    override def toProto(e: PhaseSucceeded): PhaseSucceededPO = PhaseSucceededPO(
      phase = PhaseConv.toProto(e.phase),
      transactionId = e.transactionId
    )

    override def fromProto(p: PhaseSucceededPO): PhaseSucceeded = PhaseSucceeded(
      phase = PhaseConv.fromProto(p.phase),
      transactionId = p.transactionId
    )
  }

  object PhaseFailedConv extends ProtoConverter[PhaseFailed, PhaseFailedPO] {
    override def toProto(e: PhaseFailed): PhaseFailedPO = PhaseFailedPO(
      phase = PhaseConv.toProto(e.phase),
      transactionId = e.transactionId
    )

    override def fromProto(p: PhaseFailedPO): PhaseFailed = PhaseFailed(
      phase = PhaseConv.fromProto(p.phase),
      transactionId = p.transactionId
    )
  }

  object StepGroupSucceededConv extends ProtoConverter[StepGroupSucceeded, StepGroupSucceededPO] {
    override def toProto(e: StepGroupSucceeded): StepGroupSucceededPO = StepGroupSucceededPO(
      phase = PhaseConv.toProto(e.phase),
      group = e.group,
      transactionId = e.transactionId
    )

    override def fromProto(p: StepGroupSucceededPO): StepGroupSucceeded = StepGroupSucceeded(
      phase = PhaseConv.fromProto(p.phase),
      group = p.group,
      transactionId = p.transactionId
    )
  }

  object TransactionRetriedConv extends ProtoConverter[TransactionRetried, TransactionRetriedPO] {
    override def toProto(e: TransactionRetried): TransactionRetriedPO = TransactionRetriedPO(
      transactionId = e.transactionId,
      phase = PhaseConv.toProto(e.phase)
    )

    override def fromProto(p: TransactionRetriedPO): TransactionRetried = TransactionRetried(
      transactionId = p.transactionId,
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

  object TransactionSuspendedConv extends ProtoConverter[TransactionSuspended, TransactionSuspendedPO] {
    override def toProto(e: TransactionSuspended): TransactionSuspendedPO = TransactionSuspendedPO(
      transactionId = e.transactionId,
      reason = e.reason
    )

    override def fromProto(p: TransactionSuspendedPO): TransactionSuspended = TransactionSuspended(
      transactionId = p.transactionId,
      reason = p.reason
    )
  }

  object TransactionResolvedConv extends ProtoConverter[TransactionResolved, TransactionResolvedPO] {
    override def toProto(e: TransactionResolved): TransactionResolvedPO = TransactionResolvedPO(
      transactionId = e.transactionId
    )

    override def fromProto(p: TransactionResolvedPO): TransactionResolved = TransactionResolved(
      transactionId = p.transactionId
    )
  }

  object CoordinatorStateConv extends ProtoConverter[State, CoordinatorStatePO] {
    override def toProto(s: State): CoordinatorStatePO = CoordinatorStatePO(
      transactionId = s.transactionId.getOrElse(""),
      steps = s.steps.map(SagaStepConv.toProto),
      currentPhase = PhaseConv.toProto(s.currentPhase),
      status = s.status match {
        case Created => CoordinatorStatusPO.TRANSACTION_CREATED
        case InProgress => CoordinatorStatusPO.TRANSACTION_IN_PROGRESS
        case Completed => CoordinatorStatusPO.TRANSACTION_COMPLETED
        case Failed => CoordinatorStatusPO.TRANSACTION_FAILED
        case Compensating => CoordinatorStatusPO.TRANSACTION_COMPENSATING
        case Suspended => CoordinatorStatusPO.TRANSACTION_SUSPENDED
      },
      traceId = s.traceId,
      singleStep = s.singleStep,
      isPaused = s.isPaused,
      currentStepGroup = s.currentStepGroup
    )

    override def fromProto(p: CoordinatorStatePO): State = State(
      transactionId = Some(p.transactionId).filter(_.nonEmpty),
      steps = p.steps.map(SagaStepConv.fromProto).toList,
      currentPhase = PhaseConv.fromProto(p.currentPhase),
      status = p.status match {
        case CoordinatorStatusPO.TRANSACTION_CREATED => Created
        case CoordinatorStatusPO.TRANSACTION_IN_PROGRESS => InProgress
        case CoordinatorStatusPO.TRANSACTION_COMPLETED => Completed
        case CoordinatorStatusPO.TRANSACTION_FAILED => Failed
        case CoordinatorStatusPO.TRANSACTION_COMPENSATING => Compensating
        case CoordinatorStatusPO.TRANSACTION_SUSPENDED => Suspended
        case _ => Created
      },
      traceId = p.traceId,
      singleStep = p.singleStep,
      isPaused = p.isPaused,
      currentStepGroup = if (p.currentStepGroup == 0) 1 else p.currentStepGroup
    )
  }
}
