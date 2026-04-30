package net.imadz.infra.saga.serialization

import akka.actor.ExtendedActorSystem
import akka.serialization.Serializers
import com.google.protobuf.ByteString
import net.imadz.common.serialization.{PrimitiveConverter, SerializationExtension}
import net.imadz.infra.saga.SagaParticipant.{NonRetryableFailure, RetryableFailure, RetryableOrNotException, SagaResult}
import net.imadz.infra.saga.SagaPhase._
import net.imadz.infra.saga.StepExecutor.{Event, ExecutionStarted, ManualFixCompleted, OperationFailed, OperationSucceeded, RetryScheduled}
import net.imadz.infra.saga.proto.saga_v2._
import net.imadz.infra.saga.{SagaParticipant, SagaTransactionStep}

import scala.concurrent.duration._

trait SagaExecutorConverter extends PrimitiveConverter {
  def system: ExtendedActorSystem

  protected lazy val serialization = akka.serialization.SerializationExtension(system)
  protected lazy val extension = SerializationExtension(system)

  object RetryScheduledConv extends ProtoConverter[RetryScheduled, RetryScheduledPO] {
    override def toProto(domain: RetryScheduled): RetryScheduledPO = RetryScheduledPO(
      retryCount = domain.retryCount,
      transactionId = domain.transactionId,
      stepId = domain.stepId,
      phase = domain.phase,
      traceId = domain.traceId
    )

    override def fromProto(proto: RetryScheduledPO): RetryScheduled = {
      RetryScheduled(proto.transactionId, proto.stepId, proto.phase, proto.traceId, proto.retryCount)
    }
  }

  object FailedConv extends ProtoConverter[OperationFailed, OperationFailedPO] {
    override def toProto(domain: OperationFailed): OperationFailedPO = OperationFailedPO(
      error = Some(RetryableOrNotExceptionConv.toProto(domain.error)),
      transactionId = domain.transactionId,
      stepId = domain.stepId,
      phase = domain.phase,
      traceId = domain.traceId
    )

    override def fromProto(proto: OperationFailedPO): OperationFailed = {
      OperationFailed(
        proto.transactionId,
        proto.stepId,
        proto.phase,
        proto.traceId,
        RetryableOrNotExceptionConv.fromProto(proto.error.getOrElse(throw new IllegalArgumentException(s"proto.error should not be empty")))
      )
    }
  }

  object ExecutionStartedConv extends ProtoConverter[ExecutionStarted[_, _, _], ExecutionStartedPO] {

    override def toProto(domain: ExecutionStarted[_, _, _]): ExecutionStartedPO = {
      ExecutionStartedPO(
        transactionId = domain.transactionId,
        transactionStep = Some(SagaStepConv.toProto(domain.transactionStep)), // 调用 Trait
        replyToPath = domain.replyToPath,
        traceId = domain.traceId
      )
    }

    override def fromProto(proto: ExecutionStartedPO): ExecutionStarted[_, _, _] = {
      ExecutionStarted(
        transactionId = proto.transactionId,
        transactionStep = proto.transactionStep
          .map(SagaStepConv.fromProto)
          .getOrElse(throw new IllegalArgumentException(s"proto.startedEvent.transactionStep should not be None: ${proto.transactionId}")),
        replyToPath = proto.replyToPath,
        traceId = proto.traceId
      )
    }
  }

  object OperationSucceededConv extends ProtoConverter[OperationSucceeded[_], OperationSucceededPO] {

    override def toProto(domain: OperationSucceeded[_]): OperationSucceededPO = {
      val resultRef = domain.result.asInstanceOf[AnyRef]
      val serializer = serialization.findSerializerFor(resultRef)
      val bytes = serializer.toBinary(resultRef)
      val manifest = Serializers.manifestFor(serializer, resultRef)

      OperationSucceededPO(
        resultType = manifest,
        result = ByteString.copyFrom(bytes),
        transactionId = domain.transactionId,
        stepId = domain.stepId,
        phase = domain.phase,
        traceId = domain.traceId
      )
    }

    override def fromProto(proto: OperationSucceededPO): OperationSucceeded[_] = {
      val result = if (proto.result.isEmpty) {
        SagaResult.empty[Any]()
      } else {
        serialization.deserialize(
          proto.result.toByteArray,
          serialization.serializerFor(classOf[SagaResult[Any]]).identifier,
          proto.resultType
        ).getOrElse(throw new RuntimeException(s"Failed to deserialize result of type ${proto.resultType}"))
          .asInstanceOf[SagaResult[Any]]
      }
      OperationSucceeded(proto.transactionId, proto.stepId, proto.phase, proto.traceId, result)
    }
  }

  object ManualFixCompletedConv extends ProtoConverter[ManualFixCompleted[_], ManualFixCompletedPO] {

    override def toProto(domain: ManualFixCompleted[_]): ManualFixCompletedPO = {
      val resultRef = domain.result.asInstanceOf[AnyRef]
      val serializer = serialization.findSerializerFor(resultRef)
      val bytes = serializer.toBinary(resultRef)
      val manifest = Serializers.manifestFor(serializer, resultRef)

      ManualFixCompletedPO(
        resultType = manifest,
        result = ByteString.copyFrom(bytes),
        transactionId = domain.transactionId,
        stepId = domain.stepId,
        phase = domain.phase,
        traceId = domain.traceId
      )
    }

    override def fromProto(proto: ManualFixCompletedPO): ManualFixCompleted[_] = {
      val result = if (proto.result.isEmpty) {
        SagaResult.empty[Any]()
      } else {
        serialization.deserialize(
          proto.result.toByteArray,
          serialization.serializerFor(classOf[SagaResult[Any]]).identifier,
          proto.resultType
        ).getOrElse(throw new RuntimeException(s"Failed to deserialize result of type ${proto.resultType}"))
          .asInstanceOf[SagaResult[Any]]
      }
      ManualFixCompleted(proto.transactionId, proto.stepId, proto.phase, proto.traceId, result)

    }
  }

  object RetryableOrNotExceptionConv extends ProtoConverter[RetryableOrNotException, RetryableOrNotExceptionPO] {
    override def toProto(err: RetryableOrNotException): RetryableOrNotExceptionPO = RetryableOrNotExceptionPO(
      message = if (err.message != null) err.message else "",
      isRetryable = err.isInstanceOf[RetryableFailure],
    )

    override def fromProto(proto: RetryableOrNotExceptionPO): RetryableOrNotException = {
      if (proto.isRetryable) RetryableFailure(proto.message)
      else NonRetryableFailure(proto.message)
    }
  }

  object SagaStepConv extends ProtoConverter[SagaTransactionStep[_, _, _], SagaTransactionStepPO] {

    def toProto(step: SagaTransactionStep[_, _, _]): SagaTransactionStepPO = {
      // 1. 找策略
      val strategy = extension.strategyFor(step.participant.getClass)
      // 2. 转字节
      val payloadBytes = strategy.toBinary(step.participant)
      val typeName = strategy.manifest

      // 3. 组装 PO
      SagaTransactionStepPO(
        stepId = step.stepId,
        // Phase 转换逻辑
        phase = step.phase match {
          case PreparePhase => TransactionPhasePO.PREPARE_PHASE
          case CommitPhase => TransactionPhasePO.COMMIT_PHASE
          case CompensatePhase => TransactionPhasePO.COMPENSATE_PHASE
        },
        maxRetries = step.maxRetries,
        timeoutDurationMillis = step.timeoutDuration.toMillis,
        retryWhenRecoveredOngoing = step.retryWhenRecoveredOngoing,
        participant = Some(SagaParticipantPO(typeName, ByteString.copyFrom(payloadBytes))),
        participantType = typeName,
        stepGroup = step.stepGroup
      )
    }

    def fromProto(proto: SagaTransactionStepPO): SagaTransactionStep[Any, Any, Any] = {
      val participantPO = proto.participant.getOrElse(throw new IllegalArgumentException("SagaTransactionStepPO.participant should not be empty"))
      val strategy = extension.strategyFor(participantPO.typeName)
      val participant = strategy.fromBinary(participantPO.payload.toByteArray).asInstanceOf[SagaParticipant[Any, Any, Any]]

      SagaTransactionStep(
        stepId = proto.stepId,
        phase = proto.phase match {
          case TransactionPhasePO.PREPARE_PHASE => PreparePhase
          case TransactionPhasePO.COMMIT_PHASE => CommitPhase
          case TransactionPhasePO.COMPENSATE_PHASE => CompensatePhase
          case _ => PreparePhase
        },
        participant = participant,
        maxRetries = proto.maxRetries,
        timeoutDuration = proto.timeoutDurationMillis.millis,
        retryWhenRecoveredOngoing = proto.retryWhenRecoveredOngoing,
        stepGroup = proto.stepGroup
      )
    }
  }
}
