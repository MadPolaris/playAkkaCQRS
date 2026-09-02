package net.imadz.infra.saga.serialization

import akka.actor.ExtendedActorSystem
import akka.serialization.Serializers
import com.google.protobuf.ByteString
import net.imadz.common.serialization.PrimitiveConverter
import net.imadz.infra.saga.SagaParticipant.{NonRetryableFailure, RetryableFailure, RetryableOrNotException}
import net.imadz.infra.saga.SagaPhase._
import net.imadz.infra.saga.StepExecutor.{Event, ExecutionStarted, ManualFixCompleted, OperationFailed, RetryScheduled}
import net.imadz.infra.saga.proto.saga_v3._

import scala.concurrent.duration._

/**
 * Proto converters for StepExecutor events. Participants are NOT converted — the journal
 * only carries static step descriptors; participants are rebuilt from the registered
 * SagaDefinition at recovery time.
 */
trait SagaExecutorConverter extends PrimitiveConverter {
  def system: ExtendedActorSystem

  protected lazy val serialization = akka.serialization.SerializationExtension(system)

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

  object StepDescriptorConv extends ProtoConverter[net.imadz.infra.saga.StepDescriptor, StepDescriptorPO] {
    override def toProto(d: net.imadz.infra.saga.StepDescriptor): StepDescriptorPO = StepDescriptorPO(
      stepId = d.stepId,
      phase = d.phase match {
        case PreparePhase    => TransactionPhasePO.PREPARE_PHASE
        case CommitPhase     => TransactionPhasePO.COMMIT_PHASE
        case CompensatePhase => TransactionPhasePO.COMPENSATE_PHASE
      },
      participantName = d.participantName,
      stepGroup = d.stepGroup,
      maxRetries = d.maxRetries,
      timeoutDurationMillis = d.timeoutDuration.toMillis,
      retryWhenRecoveredOngoing = d.retryWhenRecoveredOngoing,
      circuitBreaker = d.circuitBreaker.map(cb => CircuitBreakerPO(cb.maxFailures, cb.callTimeout.toMillis, cb.resetTimeout.toMillis))
    )

    override def fromProto(p: StepDescriptorPO): net.imadz.infra.saga.StepDescriptor =
      net.imadz.infra.saga.StepDescriptor(
        stepId = p.stepId,
        phase = p.phase match {
          case TransactionPhasePO.PREPARE_PHASE    => PreparePhase
          case TransactionPhasePO.COMMIT_PHASE     => CommitPhase
          case TransactionPhasePO.COMPENSATE_PHASE => CompensatePhase
          case _                                   => PreparePhase
        },
        participantName = p.participantName,
        stepGroup = if (p.stepGroup == 0) 1 else p.stepGroup,
        maxRetries = p.maxRetries,
        timeoutDuration = p.timeoutDurationMillis.millis,
        retryWhenRecoveredOngoing = p.retryWhenRecoveredOngoing,
        circuitBreaker = p.circuitBreaker.map(cb =>
          net.imadz.infra.saga.StepExecutor.CircuitBreakerSettings(cb.maxFailures, cb.callTimeoutMillis.millis, cb.resetTimeoutMillis.millis))
      )
  }

  object ExecutionStartedConv extends ProtoConverter[ExecutionStarted, ExecutionStartedPO] {

    override def toProto(domain: ExecutionStarted): ExecutionStartedPO = {
      ExecutionStartedPO(
        transactionId = domain.transactionId,
        step = Some(StepDescriptorConv.toProto(domain.step)),
        replyToPath = domain.replyToPath,
        traceId = domain.traceId
      )
    }

    override def fromProto(proto: ExecutionStartedPO): ExecutionStarted = {
      ExecutionStarted(
        transactionId = proto.transactionId,
        step = proto.step
          .map(StepDescriptorConv.fromProto)
          .getOrElse(throw new IllegalArgumentException(s"proto.startedEvent.step should not be None: ${proto.transactionId}")),
        replyToPath = proto.replyToPath,
        traceId = proto.traceId
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
        net.imadz.infra.saga.StepExecutor.OperationSucceeded(net.imadz.infra.saga.SagaParticipant.SagaResult.empty[Any]())
      } else {
        val clazz = system.dynamicAccess.getClassFor[Event](proto.resultType).getOrElse(classOf[java.io.Serializable])
        serialization.deserialize(
            proto.result.toByteArray,
            serialization.serializerFor(clazz).identifier,
            proto.resultType
          ).getOrElse(throw new RuntimeException(s"Failed to deserialize result of type ${proto.resultType}"))
          .asInstanceOf[Event]
      }
    }
  }

  object ManualFixCompletedConv extends ProtoConverter[Event, ManualFixCompletedPO] {

    override def toProto(domain: Event): ManualFixCompletedPO = {
      // 使用 Akka Serialization 将结果转为字节
      val serializer = serialization.findSerializerFor(domain.asInstanceOf[AnyRef])
      val bytes = serializer.toBinary(domain.asInstanceOf[AnyRef])
      val manifest = Serializers.manifestFor(serializer, domain.asInstanceOf[AnyRef])

      ManualFixCompletedPO(
        resultType = manifest,
        result = ByteString.copyFrom(bytes)
      )
    }

    override def fromProto(proto: ManualFixCompletedPO): Event = {
      if (proto.result.isEmpty) {
        net.imadz.infra.saga.StepExecutor.ManualFixCompleted(net.imadz.infra.saga.SagaParticipant.SagaResult.empty[Any]())
      } else {
        val clazz = system.dynamicAccess.getClassFor[Event](proto.resultType).getOrElse(classOf[java.io.Serializable])
        serialization.deserialize(
          proto.result.toByteArray,
          serialization.serializerFor(clazz).identifier,
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

}
