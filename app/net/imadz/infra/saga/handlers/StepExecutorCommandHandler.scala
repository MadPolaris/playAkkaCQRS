package net.imadz.infra.saga.handlers
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.TimerScheduler
import akka.pattern.CircuitBreaker
import akka.persistence.typed.scaladsl.Effect
import net.imadz.infra.saga.SagaParticipant._
import net.imadz.infra.saga.SagaPhase._
import net.imadz.infra.saga.{SagaParticipant, SagaTransactionStep}
import net.imadz.infra.saga.StepExecutor._
import org.slf4j.LoggerFactory

import scala.concurrent.duration.FiniteDuration
object StepExecutorCommandHandler {
   def commandHandler[E, R](
                                    context: akka.actor.typed.scaladsl.ActorContext[Command],
                                    timers: TimerScheduler[Command],
                                    defaultMaxRetries: Int,
                                    initialRetryDelay: FiniteDuration,
                                    circuitBreaker: CircuitBreaker
                                  ): (State[E, R], Command) => Effect[Event, State[E, R]] = { (state, command) =>
    command match {
      case Start(transactionId, step, replyTo: Some[ActorRef[StepResult[E, R]]]) if state.canStart =>
        Effect
          .persist(ExecutionStarted(transactionId, step, serializeActorRef(replyTo)))
          .thenRun(_ => executeOperation(context, step.phase, step, transactionId, circuitBreaker, replyTo))

      case RecoverExecution(transactionId, step, replyTo) if state.canRecover =>

        Effect
          .persist(ExecutionStarted(transactionId, step, serializeActorRef(replyTo)))
          .thenRun(_ => executeOperation(context, step.phase, step, transactionId, circuitBreaker, replyTo))


      case OperationResponse(Right(result), replyTo: Option[ActorRef[StepResult[E, R]]]) if state.status == Ongoing =>
        Effect
          .persist(OperationSucceeded(result))
          .thenRun(updatedState => updatedState.status match {
            case Succeed => // Notify success
              replyTo.foreach(_ ! StepCompleted[E, R](state.transactionId.get, result.asInstanceOf[SagaResult[R]], updatedState))
            case _ => // Unexpected state
          })

      case OperationResponse(Left(error: RetryableFailure), replyTo) if state.canScheduleRetryOnFailure(defaultMaxRetries) =>

        val nextRetry = state.retries + 1
        val nextDelay = calculateBackoffDelay(initialRetryDelay, nextRetry)

        Effect
          .persist(List(OperationFailed(error), RetryScheduled(nextRetry)))
          .thenRun(_ => scheduleRetry(timers, nextDelay, replyTo))

      case OperationResponse(Left(error), replyTo: Option[ActorRef[StepResult[E, R]]]) =>
        Effect
          .persist(OperationFailed(error))
          .thenRun(stateUpdated => replyTo.foreach(_ ! StepFailed(state.transactionId.get, error, stateUpdated)))

      case TimedOut(replyTo) if state.canScheduleRetryOnTimedOut(defaultMaxRetries) =>
        context.log.warn(s"TimedOut found ${state.retries} times")

        val nextRetry = state.retries + 1
        val nextDelay = calculateBackoffDelay(initialRetryDelay, nextRetry)

        Effect
          .persist(List(OperationFailed(RetryableFailure("timed out")), RetryScheduled(nextRetry)))
          .thenRun(_ => scheduleRetry(timers, nextDelay, replyTo))

      case TimedOut(replyTo: Option[ActorRef[StepResult[E, R]]]) =>
        Effect
          .persist(OperationFailed(RetryableFailure("timed out")))
          .thenRun(stateUpdated => replyTo.foreach(_ ! StepFailed(state.transactionId.get, RetryableFailure("timed out"), stateUpdated)))

      case RetryOperation(replyTo: Option[ActorRef[StepResult[E, R]]]) if state.canRetry =>
        state.step.zip(state.transactionId).map {
          case (step, trxId) =>
            Effect.none[Event, State[E, R]]
              .thenRun(_ => executeOperation[E, R](context, step.phase, step, state.transactionId.get, circuitBreaker, replyTo))
        }.getOrElse(Effect.none)
      case msg =>
        context.log.warn(s"msg: $msg is not processed")
        Effect.none
    }
  }

  private val logger = LoggerFactory.getLogger(getClass)

  private def executeOperation[E, R](
                                      context: akka.actor.typed.scaladsl.ActorContext[Command],
                                      stepPhase: TransactionPhase,
                                      step: SagaTransactionStep[E, R],
                                      transactionId: String,
                                      circuitBreaker: CircuitBreaker,
                                      replyTo: Option[ActorRef[StepResult[E, R]]]
                                    ): Unit = {
    import context.executionContext

    context.scheduleOnce(step.timeoutDuration, context.self, TimedOut(replyTo))

    val eventualStepResult: SagaParticipant.ParticipantEffect[RetryableOrNotException, R] = stepPhase match {
      case PreparePhase =>
        step.participant.prepare(transactionId)
      case CommitPhase =>
        step.participant.commit(transactionId)
      case CompensatePhase =>
        step.participant.compensate(transactionId)
    }

    circuitBreaker.withCircuitBreaker(eventualStepResult).onComplete {
      case scala.util.Success(result: Either[RetryableOrNotException, R]) =>
        context.self ! OperationResponse(result, replyTo)
      case scala.util.Failure(exception) =>
        logger.warn(s"$exception found while processing ${step}")
        context.self ! OperationResponse(Left(NonRetryableFailure(exception.getMessage)), replyTo)
    }
  }

  private def scheduleRetry[E, R](timers: TimerScheduler[Command], delay: FiniteDuration, replyTo: Option[ActorRef[StepResult[E, R]]]): Unit = {
    timers.startSingleTimer(RetryOperation(replyTo), delay)
  }

  private def calculateBackoffDelay(initialDelay: FiniteDuration, retryCount: Int): FiniteDuration = {
    initialDelay * math.pow(2, retryCount - 1).toLong
  }


  private def serializeActorRef(replyTo: Option[ActorRef[_]]) = {
    replyTo.map(_.path.toSerializationFormat).getOrElse("")
  }

}
