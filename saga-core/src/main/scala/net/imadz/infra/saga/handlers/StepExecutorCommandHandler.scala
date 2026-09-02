package net.imadz.infra.saga.handlers

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.TimerScheduler
import akka.pattern.CircuitBreaker
import akka.persistence.typed.scaladsl.Effect
import net.imadz.infra.saga.SagaParticipant._
import net.imadz.infra.saga.SagaPhase._
import net.imadz.infra.saga.StepExecutor._
import net.imadz.infra.saga.{SagaParticipant, SagaProgressEvent, SagaTransactionStep, StepDescriptor}
import org.slf4j.LoggerFactory

import scala.concurrent.duration.FiniteDuration

object StepExecutorCommandHandler {
  private val StepTimeoutKey = "StepTimeout"
  private val StepRetryKey = "StepRetry"

  def commandHandler[E, R, C](
                               actorContext: akka.actor.typed.scaladsl.ActorContext[Command],
                               context: C,
                               timers: TimerScheduler[Command],
                               defaultMaxRetries: Int,
                               initialRetryDelay: FiniteDuration,
                               defaultBreaker: CircuitBreaker,
                               liveStep: LiveStepHolder[E, R, C]
                             ): (State[E, R, C], Command) => Effect[Event, State[E, R, C]] = { (state, command) =>
    command match {
      // Cached terminal replies — re-attachment after coordinator recovery never re-executes.
      case Attach(transactionId, step, replyTo, _) if state.status == Succeed =>
        val typedReplyTo = replyTo.asInstanceOf[Option[ActorRef[StepResult[E, R, C]]]]
        typedReplyTo.foreach(_ ! StepCompleted[E, R, C](transactionId, step.stepId, state.result.getOrElse(SagaResult.empty[R]())))
        Effect.none

      case Attach(transactionId, step, replyTo, _) if state.status == Failed =>
        val typedReplyTo = replyTo.asInstanceOf[Option[ActorRef[StepResult[E, R, C]]]]
        typedReplyTo.foreach(_ ! StepFailed[E, R, C](transactionId, step.stepId, state.lastError.getOrElse(NonRetryableFailure("Unknown error"))))
        Effect.none

      case Attach(transactionId, step, replyTo, traceId) if state.status == Created =>
        actorContext.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(SagaProgressEvent.StepOngoing(transactionId, step.stepId, step.phase.toString, traceId))
        liveStep.current = Some(step.asInstanceOf[SagaTransactionStep[E, R, C]])
        val breaker = breakerFor(step, defaultBreaker, actorContext.system.classicSystem.scheduler)
        liveStep.breaker = breaker
        Effect
          .persist(ExecutionStarted(transactionId, StepDescriptor.of(step), serializeActorRef(replyTo), traceId))
          .thenRun(_ => executeOperation(actorContext, context, timers, step, transactionId, traceId, breaker, attempt = state.retries + 1, replyTo))

      // Sole recovery trigger: the executor never self-executes after replay (B1 fix —
      // the old RecoveryHandler Ongoing self-execution branch was removed).
      case Attach(transactionId, step, replyTo, traceId) if state.status == Ongoing =>
        liveStep.current = Some(step.asInstanceOf[SagaTransactionStep[E, R, C]])
        if (step.retryWhenRecoveredOngoing) {
          actorContext.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(SagaProgressEvent.StepOngoing(transactionId, step.stepId, step.phase.toString, traceId))
          val breaker = breakerFor(step, defaultBreaker, actorContext.system.classicSystem.scheduler)
          liveStep.breaker = breaker
          // Generation-preserving recovery: same (txId, stepId, attempt) key as the interrupted
          // attempt so participants can dedupe; retries is unchanged by ExecutionStarted.
          Effect
            .persist(ExecutionStarted(transactionId, StepDescriptor.of(step), serializeActorRef(replyTo), traceId))
            .thenRun(_ => executeOperation(actorContext, context, timers, step, transactionId, traceId, breaker, attempt = state.retries + 1, replyTo))
        } else {
          // FailIfOngoing: the outcome of the interrupted call is unknown — fail the step and
          // let the coordinator compensate. Persisted so the terminal reply stays consistent.
          Effect
            .persist(OperationFailed(NonRetryableFailure("Step was ongoing at recovery and policy is FailIfOngoing")))
            .thenRun { _: State[E, R, C] =>
              val typedReplyTo = replyTo.asInstanceOf[Option[ActorRef[StepResult[E, R, C]]]]
              typedReplyTo.foreach(_ ! StepFailed[E, R, C](transactionId, step.stepId, NonRetryableFailure("Step was ongoing at recovery and policy is FailIfOngoing")))
            }
            .thenStop()
        }

      // Stale responses from superseded generations are dropped (closes the late-response
      // double-side-effect window between TimedOut and RetryScheduled).
      case OperationResponse(_, attempt, _) if attempt != state.retries + 1 =>
        actorContext.log.warn(s"TrxId: ${state.transactionId} | Dropping stale OperationResponse (attempt $attempt, current generation ${state.retries + 1})")
        Effect.none

      case OperationResponse(Right(result), _, replyTo: Option[ActorRef[StepResult[E, R, C]]]) if state.status == Ongoing =>
        timers.cancel(StepTimeoutKey)
        Effect
          .persist(OperationSucceeded(result))
          .thenRun((updatedState: State[E, R, C]) => updatedState.status match {
            case Succeed => // Notify success
              actorContext.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(SagaProgressEvent.StepCompleted(state.transactionId.get, state.stepDescriptor.map(_.stepId).getOrElse(""), state.stepDescriptor.map(_.phase.toString).getOrElse(""), state.traceId.getOrElse("")))
              replyTo.foreach(_ ! StepCompleted[E, R, C](state.transactionId.get, state.stepDescriptor.map(_.stepId).getOrElse(""), result))
            case _ => // Unexpected state
          })
          .thenStop()

      case OperationResponse(Left(error: RetryableFailure), attempt, replyTo) if attemptMatchesGeneration(state, attempt) && state.canScheduleRetryOnFailure(defaultMaxRetries) =>
        timers.cancel(StepTimeoutKey)
        val nextRetry = state.retries + 1
        val nextDelay = calculateBackoffDelay(initialRetryDelay, nextRetry)

        actorContext.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(SagaProgressEvent.StepFailed(state.transactionId.get, state.stepDescriptor.map(_.stepId).getOrElse(""), state.stepDescriptor.map(_.phase.toString).getOrElse(""), s"Retryable failure (attempt $nextRetry): ${error.message}", state.traceId.getOrElse("")))

        Effect
          .persist(List(OperationFailed(error), RetryScheduled(nextRetry)))
          .thenRun(_ => scheduleRetry(timers, nextDelay, attempt = nextRetry + 1, replyTo))

      case OperationResponse(Left(error), attempt, replyTo: Option[ActorRef[StepResult[E, R, C]]]) if attemptMatchesGeneration(state, attempt) =>
        timers.cancel(StepTimeoutKey)
        actorContext.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(SagaProgressEvent.StepFailed(state.transactionId.get, state.stepDescriptor.map(_.stepId).getOrElse(""), state.stepDescriptor.map(_.phase.toString).getOrElse(""), error.message, state.traceId.getOrElse("")))
        Effect
          .persist(OperationFailed(error))
          .thenRun((_: State[E, R, C]) => replyTo.foreach(_ ! StepFailed(state.transactionId.get, state.stepDescriptor.map(_.stepId).getOrElse(""), error)))
          .thenStop()

      case TimedOut(attempt, replyTo) if state.status == Ongoing && attemptMatchesGeneration(state, attempt) && state.canScheduleRetryOnTimedOut(defaultMaxRetries) =>
        actorContext.log.warn(s"TimedOut found ${state.retries} times")

        val nextRetry = state.retries + 1
        val nextDelay = calculateBackoffDelay(initialRetryDelay, nextRetry)

        actorContext.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(SagaProgressEvent.StepFailed(state.transactionId.get, state.stepDescriptor.map(_.stepId).getOrElse(""), state.stepDescriptor.map(_.phase.toString).getOrElse(""), s"Timeout (attempt $nextRetry)", state.traceId.getOrElse("")))

        Effect
          .persist(List(OperationFailed(RetryableFailure("timed out")), RetryScheduled(nextRetry)))
          .thenRun(_ => scheduleRetry(timers, nextDelay, attempt = nextRetry + 1, replyTo))

      case TimedOut(attempt, replyTo: Option[ActorRef[StepResult[E, R, C]]]) if state.status == Ongoing && attemptMatchesGeneration(state, attempt) =>
        actorContext.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(SagaProgressEvent.StepFailed(state.transactionId.get, state.stepDescriptor.map(_.stepId).getOrElse(""), state.stepDescriptor.map(_.phase.toString).getOrElse(""), "Timeout limit reached", state.traceId.getOrElse("")))
        Effect
          .persist(OperationFailed(RetryableFailure("timed out")))
          .thenRun((_: State[E, R, C]) => replyTo.foreach(_ ! StepFailed(state.transactionId.get, state.stepDescriptor.map(_.stepId).getOrElse(""), RetryableFailure("timed out"))))
          .thenStop()

      case TimedOut(attempt, _) =>
        actorContext.log.info(s"TrxId: ${state.transactionId} | Ignoring TimedOut (attempt $attempt) because step is in ${state.status} state or generation mismatch")
        Effect.none

      case RetryOperation(attempt, replyTo: Option[ActorRef[StepResult[E, R, C]]]) if state.canRetry && attemptMatchesGeneration(state, attempt) =>
        state.stepDescriptor.zip(state.transactionId).zip(state.traceId).flatMap {
          case ((descriptor, trxId), traceId) =>
            liveStep.current.map { step =>
              Effect.none[Event, State[E, R, C]]
                .thenRun(_ => executeOperation[E, R, C](actorContext, context, timers, step, trxId, traceId, liveStep.breaker, attempt = attempt, replyTo = replyTo))
            }
        }.getOrElse {
          actorContext.log.warn(s"TrxId: ${state.transactionId} | RetryOperation without a live step — dropped")
          Effect.none
        }

      case qs: QueryStatus[E, R, C] =>
        qs.replyTo ! state
        Effect.none

      case ManualFix(replyTo) =>
        timers.cancel(StepTimeoutKey)
        actorContext.log.info(s"ManualFix received for transaction ${state.transactionId.getOrElse("unknown")} step ${state.stepDescriptor.map(_.stepId).getOrElse("unknown")}")
        val typedReplyTo = replyTo.asInstanceOf[Option[ActorRef[StepResult[E, R, C]]]]
        // 假设手动修复成功，返回一个空的成功的 SagaResult
        val manualResult = SagaResult.empty[R]()
        val stepId = state.stepDescriptor.map(_.stepId).getOrElse("unknown")
        val phase = state.stepDescriptor.map(_.phase.toString).getOrElse("")
        Effect
          .persist(ManualFixCompleted(manualResult))
          .thenRun((updatedState: State[E, R, C]) => {
             actorContext.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(
               SagaProgressEvent.StepCompleted(state.transactionId.getOrElse(""), stepId, phase, state.traceId.getOrElse(""), isManual = true)
             )
             typedReplyTo.foreach(_ ! StepCompleted[E, R, C](state.transactionId.getOrElse(""), stepId, manualResult))
          })
          .thenStop()

      case msg =>
        actorContext.log.warn(s"msg: $msg is not processed")
        Effect.none
    }
  }

  /** Generation gate: a response belongs to the in-flight operation iff attempt == retries + 1. */
  private def attemptMatchesGeneration[E, R, C](state: State[E, R, C], attempt: Int): Boolean = attempt == state.retries + 1

  private val logger = LoggerFactory.getLogger(getClass)

  private def breakerFor[E, R, C](step: SagaTransactionStep[E, R, C], defaultBreaker: CircuitBreaker, scheduler: akka.actor.Scheduler): CircuitBreaker =
    step.circuitBreaker match {
      case Some(settings) => CircuitBreaker(scheduler, settings.maxFailures, settings.callTimeout, settings.resetTimeout)
      case None           => defaultBreaker
    }

  private def executeOperation[E, R, C](
                                         actorContext: akka.actor.typed.scaladsl.ActorContext[Command],
                                         context: C,
                                         timers: TimerScheduler[Command],
                                         step: SagaTransactionStep[E, R, C],
                                         transactionId: String,
                                         traceId: String,
                                         circuitBreaker: CircuitBreaker,
                                         attempt: Int,
                                         replyTo: Option[ActorRef[StepResult[E, R, C]]]
                                       ): Unit = {
    import actorContext.executionContext

    timers.startSingleTimer(StepTimeoutKey, TimedOut(attempt, replyTo), step.timeoutDuration)

    val eventualStepResult: SagaParticipant.ParticipantEffect[RetryableOrNotException, R] = step.phase match {
      case PreparePhase =>
        step.participant.prepare(transactionId, context, traceId)
      case CommitPhase =>
        step.participant.commit(transactionId, context, traceId)
      case CompensatePhase =>
        step.participant.compensate(transactionId, context, traceId)
    }

    circuitBreaker.withCircuitBreaker(eventualStepResult).onComplete {
      case scala.util.Success(result: Either[RetryableOrNotException, SagaResult[R]]) =>
        actorContext.self ! OperationResponse(result, attempt, replyTo)
      case scala.util.Failure(exception) =>
        logger.error(s"Operation failed with exception for step ${step.stepId}", exception)
        actorContext.self ! OperationResponse(Left(NonRetryableFailure(s"Operation failed: ${exception.getMessage}")), attempt, replyTo)
    }
  }

  private def scheduleRetry[E, R, C](timers: TimerScheduler[Command], delay: FiniteDuration, attempt: Int, replyTo: Option[ActorRef[StepResult[E, R, C]]]): Unit = {
    // Fixed key so a newer generation replaces any pending retry timer of the same step.
    timers.startSingleTimer(StepRetryKey, RetryOperation(attempt, replyTo), delay)
  }

  private def calculateBackoffDelay(initialDelay: FiniteDuration, retryCount: Int): FiniteDuration = {
    initialDelay * math.pow(2, retryCount - 1).toLong
  }

  private def serializeActorRef(replyTo: Option[ActorRef[_]]) = {
    replyTo.map(_.path.toSerializationFormat).getOrElse("")
  }

}
