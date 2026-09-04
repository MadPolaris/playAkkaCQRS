package net.imadz.infra.saga.persistence.converters

import net.imadz.common.serialization.PrimitiveConverter
import net.imadz.infra.saga.SagaPhase._
import net.imadz.infra.saga.SagaTransactionCoordinator._
import net.imadz.infra.saga.proto.saga_v3._
import net.imadz.infra.saga.serialization.SagaExecutorConverter

/**
 * SagaCoordinatorProtoConverters
 * 职责：
 * 1. 定义 SagaTransactionCoordinator 相关事件的 ProtoConverter
 * 2. 混入 SagaExecutorConverter 以复用 StepDescriptor 的转换能力
 * 3. 混入 PrimitiveConverter 以获得基础转换能力
 *
 * v3: TransactionStarted records (definition name+version, args, argsHash, step descriptors);
 * participants never enter the journal.
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

  object StepOutcomeConv extends ProtoConverter[StepOutcome, StepOutcomePO] {
    override def toProto(o: StepOutcome): StepOutcomePO = StepOutcomePO(
      stepId = o.stepId,
      phase = PhaseConv.toProto(o.phase),
      status = o.status
    )

    override def fromProto(p: StepOutcomePO): StepOutcome = StepOutcome(
      stepId = p.stepId,
      phase = PhaseConv.fromProto(p.phase),
      status = p.status
    )
  }

  private def outcomesToProto(outcomes: List[StepOutcome]): Seq[StepOutcomePO] =
    outcomes.map(StepOutcomeConv.toProto)

  private def outcomesFromProto(outcomes: Seq[StepOutcomePO]): List[StepOutcome] =
    outcomes.map(StepOutcomeConv.fromProto).toList

  object TransactionStartedConv extends ProtoConverter[TransactionStarted, TransactionStartedPO] {
    override def toProto(e: TransactionStarted): TransactionStartedPO = TransactionStartedPO(
      transactionId = e.transactionId,
      definitionName = e.definitionName,
      definitionVersion = e.definitionVersion,
      args = com.google.protobuf.ByteString.copyFrom(e.argsBytes),
      argsHash = e.argsHash,
      steps = e.steps.map(StepDescriptorConv.toProto),
      traceId = e.traceId,
      singleStep = e.singleStep
    )

    override def fromProto(p: TransactionStartedPO): TransactionStarted = TransactionStarted(
      transactionId = p.transactionId,
      definitionName = p.definitionName,
      definitionVersion = p.definitionVersion,
      argsBytes = p.args.toByteArray,
      argsHash = p.argsHash,
      steps = p.steps.map(StepDescriptorConv.fromProto).toList,
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
      outcomes = outcomesToProto(e.outcomes)
    )

    override def fromProto(p: PhaseSucceededPO): PhaseSucceeded = PhaseSucceeded(
      phase = PhaseConv.fromProto(p.phase),
      outcomes = outcomesFromProto(p.outcomes)
    )
  }

  object PhaseFailedConv extends ProtoConverter[PhaseFailed, PhaseFailedPO] {
    override def toProto(e: PhaseFailed): PhaseFailedPO = PhaseFailedPO(
      phase = PhaseConv.toProto(e.phase),
      outcomes = outcomesToProto(e.outcomes)
    )

    override def fromProto(p: PhaseFailedPO): PhaseFailed = PhaseFailed(
      phase = PhaseConv.fromProto(p.phase),
      outcomes = outcomesFromProto(p.outcomes)
    )
  }

  object StepGroupSucceededConv extends ProtoConverter[StepGroupSucceeded, StepGroupSucceededPO] {
    override def toProto(e: StepGroupSucceeded): StepGroupSucceededPO = StepGroupSucceededPO(
      phase = PhaseConv.toProto(e.phase),
      group = e.group,
      outcomes = outcomesToProto(e.outcomes)
    )

    override def fromProto(p: StepGroupSucceededPO): StepGroupSucceeded = StepGroupSucceeded(
      phase = PhaseConv.fromProto(p.phase),
      group = p.group,
      outcomes = outcomesFromProto(p.outcomes)
    )
  }

  object StepManuallyFixedConv extends ProtoConverter[StepManuallyFixed, StepManuallyFixedPO] {
    override def toProto(e: StepManuallyFixed): StepManuallyFixedPO = StepManuallyFixedPO(
      stepId = e.stepId,
      phase = PhaseConv.toProto(e.phase)
    )

    override def fromProto(p: StepManuallyFixedPO): StepManuallyFixed = StepManuallyFixed(
      stepId = p.stepId,
      phase = PhaseConv.fromProto(p.phase)
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
}
