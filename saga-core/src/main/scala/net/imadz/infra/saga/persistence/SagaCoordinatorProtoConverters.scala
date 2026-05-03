package net.imadz.infra.saga.persistence.converters

import akka.serialization.Serializers
import net.imadz.common.serialization.PrimitiveConverter
import net.imadz.infra.saga.SagaParticipant.{NonRetryableFailure, RetryableFailure, RetryableOrNotException, SagaResult}
import net.imadz.infra.saga.SagaPhase._
import net.imadz.infra.saga.SagaTransactionCoordinator._
import net.imadz.infra.saga.proto.saga_v2._
import net.imadz.infra.saga.serialization.SagaExecutorConverter

/**
 * SagaCoordinatorProtoConverters
 * Responsibility:
 * 1. Define ProtoConverters for SagaTransactionCoordinator events and state.
 * 2. Mix in SagaExecutorConverter to reuse Step and Participant conversion.
 * 3. Mix in PrimitiveConverter for basic conversion capabilities.
 */
trait SagaCoordinatorProtoConverters extends PrimitiveConverter with SagaExecutorConverter {


  // --- Phase Converter ---
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
      case _ => PreparePhase
    }
  }

  // --- Utility Converters ---

  object PhaseResultConv extends ProtoConverter[Either[RetryableOrNotException, Any], PhaseResultPO] {
    override def toProto(e: Either[RetryableOrNotException, Any]): PhaseResultPO = e match {
      case Left(err) => 
        PhaseResultPO(result = PhaseResultPO.Result.Error(err.message))
      case Right(res) => 
        val resRef = res.asInstanceOf[AnyRef]
        val serializer = serialization.findSerializerFor(resRef)
        val bytes = serializer.toBinary(resRef)
        val manifest = Serializers.manifestFor(serializer, resRef)
        PhaseResultPO(result = PhaseResultPO.Result.Success(com.google.protobuf.ByteString.copyFrom(bytes)), successType = manifest)
    }

    override def fromProto(p: PhaseResultPO): Either[RetryableOrNotException, Any] = p.result match {
      case PhaseResultPO.Result.Error(msg) => 
        Left(NonRetryableFailure(msg))
      case PhaseResultPO.Result.Success(bytes) => 
        val res = serialization.deserialize(
          bytes.toByteArray,
          serialization.serializerFor(classOf[SagaResult[Any]]).identifier,
          p.successType
        ).getOrElse(throw new RuntimeException(s"Failed to deserialize phase result of type ${p.successType}"))
        Right(res)
      case _ => Left(NonRetryableFailure("Unknown result"))
    }
  }

  // --- Event Converters ---

  object TransactionStartedConv extends ProtoConverter[TransactionStarted, TransactionStartedPO] {
    import scala.concurrent.duration._
    override def toProto(e: TransactionStarted): TransactionStartedPO = TransactionStartedPO(
      transactionId = e.transactionId,
      steps = e.steps.map(SagaStepConv.toProto),
      traceId = e.traceId,
      singleStep = e.singleStep,
      timeoutMillis = e.timeout.map(_.toMillis).getOrElse(0L)
    )

    override def fromProto(p: TransactionStartedPO): TransactionStarted = TransactionStarted(
      transactionId = p.transactionId,
      steps = p.steps.map(SagaStepConv.fromProto).toList,
      traceId = p.traceId,
      singleStep = p.singleStep,
      timeout = if (p.timeoutMillis > 0) Some(p.timeoutMillis.milliseconds) else None
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

  object StepGroupStartedConv extends ProtoConverter[StepGroupStarted, StepGroupStartedPO] {
    override def toProto(e: StepGroupStarted): StepGroupStartedPO = StepGroupStartedPO(
      transactionId = e.transactionId,
      phase = PhaseConv.toProto(e.phase),
      group = e.group,
      stepIds = e.stepIds.toList
    )

    override def fromProto(p: StepGroupStartedPO): StepGroupStarted = StepGroupStarted(
      transactionId = p.transactionId,
      phase = PhaseConv.fromProto(p.phase),
      group = p.group,
      stepIds = p.stepIds.toSet
    )
  }

  object StepResultReceivedConv extends ProtoConverter[StepResultReceived, StepResultReceivedPO] {
    override def toProto(e: StepResultReceived): StepResultReceivedPO = StepResultReceivedPO(
      transactionId = e.transactionId,
      stepId = e.stepId,
      result = Some(PhaseResultConv.toProto(e.result))
    )

    override def fromProto(p: StepResultReceivedPO): StepResultReceived = StepResultReceived(
      transactionId = p.transactionId,
      stepId = p.stepId,
      result = p.result.map(PhaseResultConv.fromProto).getOrElse(Left(NonRetryableFailure("Missing result")))
    )
  }

  // --- State Converter ---

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
      currentStepGroup = s.currentStepGroup,
      pendingSteps = s.pendingSteps.toList,
      phaseResults = s.phaseResults.map(PhaseResultConv.toProto),
      timeoutMillis = s.timeout.map(_.toMillis).getOrElse(0L)
    )

    override def fromProto(p: CoordinatorStatePO): State = {
      import scala.concurrent.duration._
      State(
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
        currentStepGroup = if (p.currentStepGroup == 0) 1 else p.currentStepGroup,
        pendingSteps = p.pendingSteps.toSet,
        phaseResults = p.phaseResults.map(PhaseResultConv.fromProto).toList,
        timeout = if (p.timeoutMillis > 0) Some(p.timeoutMillis.milliseconds) else None
      )
    }
  }
}
