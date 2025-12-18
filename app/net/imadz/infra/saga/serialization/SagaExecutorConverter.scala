package net.imadz.infra.saga.serialization

import akka.actor.ExtendedActorSystem
import akka.serialization.Serializers
import com.google.protobuf.ByteString
import net.imadz.common.CommonTypes.iMadzError
import net.imadz.common.serialization.{PrimitiveConverter, SerializationExtension}
import net.imadz.infra.saga.SagaParticipant.{NonRetryableFailure, RetryableFailure, RetryableOrNotException}
import net.imadz.infra.saga.SagaPhase._
import net.imadz.infra.saga.StepExecutor.{Event, ExecutionStarted, OperationFailed, RetryScheduled}
import net.imadz.infra.saga.proto.saga_v2._
import net.imadz.infra.saga.{SagaParticipant, SagaTransactionStep}

import scala.concurrent.duration._

trait SagaExecutorConverter extends PrimitiveConverter {
  def system: ExtendedActorSystem

  protected lazy val serialization = akka.serialization.SerializationExtension(system)
  protected lazy val extension = SerializationExtension(system)

  object RetryScheduledConv extends ProtoConverter[RetryScheduled, RetryScheduledPO] {
    override def toProto(domain: RetryScheduled): RetryScheduledPO = RetryScheduledPO(
      retryCount = domain.retryCount
    )

    override def fromProto(proto: RetryScheduledPO): RetryScheduled = {
      RetryScheduled(proto.retryCount)
    }
  }

  object FailedConv extends ProtoConverter[OperationFailed, OperationFailedPO] {
    override def toProto(domain: OperationFailed): OperationFailedPO = OperationFailedPO(
      Some(RetryableOrNotExceptionConv.toProto(domain.error))
    )

    override def fromProto(proto: OperationFailedPO): OperationFailed = {
      OperationFailed(RetryableOrNotExceptionConv.fromProto(proto.error.getOrElse(throw new IllegalArgumentException(s"proto.error should not be empty"))))
    }
  }

  object ExecutionStartedConv extends ProtoConverter[ExecutionStarted[_, _], ExecutionStartedPO] {

    override def toProto(domain: ExecutionStarted[_, _]): ExecutionStartedPO = {
      ExecutionStartedPO(
        transactionId = domain.transactionId,
        transactionStep = Some(SagaStepConv.toProto(domain.transactionStep)), // 调用 Trait
        replyToPath = domain.replyToPath
      )
    }

    override def fromProto(proto: ExecutionStartedPO): ExecutionStarted[_, _] = {
      ExecutionStarted(
        transactionId = proto.transactionId,
        transactionStep = proto.transactionStep
          .map(SagaStepConv.fromProto)
          .getOrElse(throw new IllegalArgumentException(s"proto.startedEvent.transactionStep should not be None: ${proto.transactionId}")),
        replyToPath = proto.replyToPath
      )
    }
  }

  object OperationSucceededConv extends ProtoConverter[Event, OperationSucceededPO] {

    override def toProto(domain: Event): OperationSucceededPO = {
      // 使用 Akka Serialization 将结果转为字节
      val serializer = serialization.findSerializerFor(domain.asInstanceOf[AnyRef])
      val bytes = serializer.toBinary(domain.asInstanceOf[AnyRef])
      val manifest = Serializers.manifestFor(serializer, domain.asInstanceOf[AnyRef])

      OperationSucceededPO(
        resultType = manifest,
        result = ByteString.copyFrom(bytes)
      )
    }

    override def fromProto(proto: OperationSucceededPO): Event = {
      if (proto.result.isEmpty) {
        null // 或者根据业务需求处理空值
      } else {
        serialization.deserialize(
            proto.result.toByteArray,
            serialization.serializerFor(classOf[Event]).identifier, // 使用默认或者根据 type 查找
            proto.resultType
          ).getOrElse(throw new RuntimeException(s"Failed to deserialize result of type ${proto.resultType}"))
          .asInstanceOf[Event]
      }
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

  object SagaStepConv extends ProtoConverter[SagaTransactionStep[_, _], SagaTransactionStepPO] {

    def toProto(step: SagaTransactionStep[_, _]): SagaTransactionStepPO = {
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
        participant = Some(SagaParticipantPO(typeName, ByteString.copyFrom(payloadBytes))),
        maxRetries = step.maxRetries,
        timeoutDurationMillis = step.timeoutDuration.toMillis,
        retryWhenRecoveredOngoing = step.retryWhenRecoveredOngoing
      )
    }

    def fromProto(stepPO: SagaTransactionStepPO): SagaTransactionStep[iMadzError, String] = {
      val genericParticipant = stepPO.participant.getOrElse(throw new IllegalArgumentException("Missing participant"))

      // 1. 找策略
      val strategy = extension.strategyFor(genericParticipant.typeName)
      // 2. 转对象
      val participant = strategy.fromBinary(genericParticipant.payload.toByteArray)
        .asInstanceOf[SagaParticipant[iMadzError, String]]

      // 3. 组装 Step
      SagaTransactionStep[iMadzError, String](
        stepId = stepPO.stepId,
        phase = stepPO.phase match {
          case TransactionPhasePO.PREPARE_PHASE => PreparePhase
          case TransactionPhasePO.COMMIT_PHASE => CommitPhase
          case TransactionPhasePO.COMPENSATE_PHASE => CompensatePhase
          case _ => PreparePhase
        },
        participant = participant,
        maxRetries = stepPO.maxRetries,
        timeoutDuration = stepPO.timeoutDurationMillis.millis,
        retryWhenRecoveredOngoing = stepPO.retryWhenRecoveredOngoing
      )

    }

  }


}