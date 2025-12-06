package net.imadz.infrastructure.persistence

import akka.persistence.typed.{EventAdapter, EventSeq}
import com.google.protobuf.ByteString
import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.infra.saga.SagaParticipant._
import net.imadz.infra.saga.SagaPhase.{CommitPhase, CompensatePhase, PreparePhase}
import net.imadz.infra.saga._
import net.imadz.infra.saga.proto.saga_v2._
import net.imadz.infra.saga.serialization.AkkaSerializationWrapper

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationLong

case class StepExecutorEventAdapter(serialization: AkkaSerializationWrapper, repository: CreditBalanceRepository, ec: ExecutionContext)
  extends EventAdapter[StepExecutor.Event, StepExecutorEventPO.Event]
  with ForSaga {
  private val stepSerializer: SagaTransactionStepSerializer = SagaTransactionStepSerializer(repository = repository, ec = ec)

  override def manifest(event: StepExecutor.Event): String = event.getClass.getName

  override def toJournal(e: StepExecutor.Event): StepExecutorEventPO.Event = e match {
    case StepExecutor.ExecutionStarted(transactionId, step, replyToPath) =>
      val stepPO = stepSerializer.serializeSagaTransactionStep(step)
      StepExecutorEventPO.Event.Started(ExecutionStartedPO(transactionId, Some(stepPO), replyToPath))

    case StepExecutor.OperationSucceeded(result) =>
      // TODO FIX
      val (bytes, manifest) = serialization.serialize(result.asInstanceOf[AnyRef])
      StepExecutorEventPO.Event.Succeed(OperationSucceededPO(result.getClass.getName, ByteString.copyFrom(bytes)))

    case StepExecutor.OperationFailed(error) =>
      val errorPO = RetryableOrNotExceptionPO(error.isInstanceOf[RetryableFailure], error.message)
      StepExecutorEventPO.Event.Failed(OperationFailedPO(Some(errorPO)))

    case StepExecutor.RetryScheduled(retryCount) =>
      StepExecutorEventPO.Event.Rescheduled(RetryScheduledPO(retryCount))
  }

  override def fromJournal(p: StepExecutorEventPO.Event, manifest: String): EventSeq[StepExecutor.Event] = {
    val event = p match {
      case StepExecutorEventPO.Event.Started(started) =>
        val step = started.transactionStep.map { stepPO =>
          SagaTransactionStep(
            stepId = stepPO.stepId,
            phase = stepPO.phase match {
              case TransactionPhasePO.PREPARE_PHASE => PreparePhase
              case TransactionPhasePO.COMMIT_PHASE => CommitPhase
              case TransactionPhasePO.COMPENSATE_PHASE => CompensatePhase
              case _ => throw new IllegalArgumentException(s"Unknown phase: ${stepPO.phase}")
            },
            participant = stepSerializer.deserializeSagaTransactionStep(stepPO).asInstanceOf[SagaParticipant[_, _]],
            maxRetries = stepPO.maxRetries,
            timeoutDuration = stepPO.timeoutDurationMillis.milliseconds,
            retryWhenRecoveredOngoing = stepPO.retryWhenRecoveredOngoing
          )
        }.getOrElse(throw new IllegalArgumentException("TransactionStep is missing"))
        StepExecutor.ExecutionStarted(started.transactionId, step, started.replyToPath)

      case StepExecutorEventPO.Event.Succeed(OperationSucceededPO(resultType, resultBytes, _)) =>
        val result = serialization.deserialize(resultBytes.toByteArray, resultType)
        StepExecutor.OperationSucceeded(result)

      case StepExecutorEventPO.Event.Failed(failed) =>
        val error = failed.error.map { errorPO =>
          if (errorPO.isRetryable) RetryableFailure(errorPO.message)
          else NonRetryableFailure(errorPO.message)
        }.getOrElse(throw new IllegalArgumentException("Error is missing"))
        StepExecutor.OperationFailed(error)

      case StepExecutorEventPO.Event.Rescheduled(rescheduled) =>
        StepExecutor.RetryScheduled(rescheduled.retryCount)
    }
    EventSeq.single(event)
  }
}