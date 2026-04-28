package net.imadz.infra.saga.handlers

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.TimerScheduler
import akka.pattern.CircuitBreaker
import akka.persistence.typed.scaladsl.Effect
import net.imadz.infra.saga.SagaParticipant._
import net.imadz.infra.saga.SagaPhase._
import net.imadz.infra.saga.StepExecutor._
import net.imadz.infra.saga.{SagaParticipant, SagaTransactionStep}
import org.slf4j.LoggerFactory

import scala.concurrent.duration.FiniteDuration

object StepExecutorCommandHandler {
  def commandHandler[E, R, C](
                               actorContext: akka.actor.typed.scaladsl.ActorContext[Command],
                               context: C,
                               timers: TimerScheduler[Command],
                               defaultMaxRetries: Int,
                               initialRetryDelay: FiniteDuration,
                               circuitBreaker: CircuitBreaker
                             ): (State[E, R, C], Command) => Effect[Event, State[E, R, C]] = { (state, command) =>
    command match {
      case startMsg @ Start(transactionId, step, replyTo, traceId) if state.status == Succeed =>
        val typedReplyTo = replyTo.asInstanceOf[Option[ActorRef[StepResult[E, R, C]]]]
        typedReplyTo.foreach(_ ! StepCompleted[E, R, C](transactionId, step.stepId, state.result.get))
        Effect.none

      case startMsg @ Start(transactionId, step, replyTo, traceId) if state.status == Failed =>
        val typedReplyTo = replyTo.asInstanceOf[Option[ActorRef[StepResult[E, R, C]]]]
        typedReplyTo.foreach(_ ! StepFailed[E, R, C](transactionId, step.stepId, state.lastError.getOrElse(NonRetryableFailure("Unknown error"))))
        Effect.none

      case Start(transactionId, step, replyTo, traceId) if state.canStart =>
        Effect
          .persist(ExecutionStarted(transactionId, step, serializeActorRef(replyTo), traceId))
          .thenRun(_ => executeOperation(actorContext, context, step.phase, step, transactionId, traceId, circuitBreaker, replyTo))

      case RecoverExecution(transactionId, step, replyTo, traceId) if state.canRecover =>

        Effect
          .persist(ExecutionStarted(transactionId, step, serializeActorRef(replyTo), traceId))
          .thenRun(_ => executeOperation(actorContext, context, step.phase, step, transactionId, traceId, circuitBreaker, replyTo))


      case OperationResponse(Right(result), replyTo: Option[ActorRef[StepResult[E, R, C]]]) if state.status == Ongoing =>
        Effect
          .persist(OperationSucceeded(result))
          .thenRun((updatedState: State[E, R, C]) => updatedState.status match {
            case Succeed => // Notify success
              replyTo.foreach(_ ! StepCompleted[E, R, C](state.transactionId.get, state.step.get.stepId, result))
            case _ => // Unexpected state
          })
          .thenStop()

      case OperationResponse(Left(error: RetryableFailure), replyTo) if state.canScheduleRetryOnFailure(defaultMaxRetries) =>

        val nextRetry = state.retries + 1
        val nextDelay = calculateBackoffDelay(initialRetryDelay, nextRetry)

        Effect
          .persist(List(OperationFailed(error), RetryScheduled(nextRetry)))
          .thenRun(_ => scheduleRetry(timers, nextDelay, replyTo))

      case OperationResponse(Left(error), replyTo: Option[ActorRef[StepResult[E, R, C]]]) =>
        Effect
          .persist(OperationFailed(error))
          .thenRun((_: State[E, R, C]) => replyTo.foreach(_ ! StepFailed(state.transactionId.get, state.step.get.stepId, error)))
          .thenStop()

      case TimedOut(replyTo) if state.status == Ongoing && state.canScheduleRetryOnTimedOut(defaultMaxRetries) =>
        actorContext.log.warn(s"TimedOut found ${state.retries} times")

        val nextRetry = state.retries + 1
        val nextDelay = calculateBackoffDelay(initialRetryDelay, nextRetry)

        Effect
          .persist(List(OperationFailed(RetryableFailure("timed out")), RetryScheduled(nextRetry)))
          .thenRun(_ => scheduleRetry(timers, nextDelay, replyTo))

      case TimedOut(replyTo: Option[ActorRef[StepResult[E, R, C]]]) if state.status == Ongoing =>
        Effect
          .persist(OperationFailed(RetryableFailure("timed out")))
          .thenRun((_: State[E, R, C]) => replyTo.foreach(_ ! StepFailed(state.transactionId.get, state.step.get.stepId, RetryableFailure("timed out"))))
          .thenStop()

      case TimedOut(_) =>
        actorContext.log.info(s"TrxId: ${state.transactionId} | Ignoring TimedOut message because step is already in ${state.status} state")
        Effect.none

      case RetryOperation(replyTo: Option[ActorRef[StepResult[E, R, C]]]) if state.canRetry =>
        state.step.zip(state.transactionId).zip(state.traceId).map {
          case ((step, trxId), traceId) =>
            Effect.none[Event, State[E, R, C]]
              .thenRun(_ => executeOperation[E, R, C](actorContext, context, step.phase, step, trxId, traceId, circuitBreaker, replyTo))
        }.getOrElse(Effect.none)

      case qs: QueryStatus[E, R, C] =>
        qs.replyTo ! state
        Effect.none

      case msg =>
        actorContext.log.warn(s"msg: $msg is not processed")
        Effect.none
    }
  }

  private val logger = LoggerFactory.getLogger(getClass)

  private def executeOperation[E, R, C](
                                         actorContext: akka.actor.typed.scaladsl.ActorContext[Command],
                                         context: C,
                                         stepPhase: TransactionPhase,
                                         step: SagaTransactionStep[E, R, C],
                                         transactionId: String,
                                         traceId: String,
                                         circuitBreaker: CircuitBreaker,
                                         replyTo: Option[ActorRef[StepResult[E, R, C]]]
                                       ): Unit = {
    import actorContext.executionContext

    actorContext.scheduleOnce(step.timeoutDuration, actorContext.self, TimedOut(replyTo))

    val eventualStepResult: SagaParticipant.ParticipantEffect[RetryableOrNotException, R] = stepPhase match {
      case PreparePhase =>
        step.participant.prepare(transactionId, context, traceId)
      case CommitPhase =>
        step.participant.commit(transactionId, context, traceId)
      case CompensatePhase =>
        step.participant.compensate(transactionId, context, traceId)
    }

    circuitBreaker.withCircuitBreaker(eventualStepResult).onComplete {
      case scala.util.Success(result: Either[RetryableOrNotException, SagaResult[R]]) =>
        actorContext.self ! OperationResponse(result, replyTo)
      case scala.util.Failure(exception) =>
        logger.warn(s"$exception found while processing ${step}")
        actorContext.self ! OperationResponse(Left(NonRetryableFailure(exception.getMessage)), replyTo)
    }
  }

  private def scheduleRetry[E, R, C](timers: TimerScheduler[Command], delay: FiniteDuration, replyTo: Option[ActorRef[StepResult[E, R, C]]]): Unit = {
    timers.startSingleTimer(RetryOperation(replyTo), delay)
  }

  private def calculateBackoffDelay(initialDelay: FiniteDuration, retryCount: Int): FiniteDuration = {
    initialDelay * math.pow(2, retryCount - 1).toLong
  }


  private def serializeActorRef(replyTo: Option[ActorRef[_]]) = {
    replyTo.map(_.path.toSerializationFormat).getOrElse("")
  }

}
