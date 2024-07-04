package net.imadz.infra.saga

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import akka.pattern.CircuitBreaker
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import com.fasterxml.jackson.annotation.JsonIgnore
import net.imadz.common.CborSerializable
import net.imadz.infra.saga.SagaParticipant.{NonRetryableFailure, RetryableFailure, RetryableOrNotException}
import net.imadz.infra.saga.SagaPhase.{CommitPhase, CompensatePhase, PreparePhase, TransactionPhase}
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

object SagaPhase {
  // Value Object
  sealed trait TransactionPhase extends CborSerializable {
    val key: String = toString
  }

  case object PreparePhase extends TransactionPhase {
    override def toString: String = "prepare"
  }

  case object CommitPhase extends TransactionPhase {
    override def toString: String = "commit"
  }

  case object CompensatePhase extends TransactionPhase {
    override def toString: String = "compensate"
  }
}

case class SagaTransactionStep[E, R](
                                      stepId: String,
                                      phase: TransactionPhase,
                                      participant: SagaParticipant[E, R],
                                      maxRetries: Int = 0,
                                      timeoutDuration: FiniteDuration = 30.seconds,
                                      retryWhenRecoveredOngoing: Boolean = true
                                    )

object StepExecutor {
  // @formatter:off
  // Commands
  sealed trait Command extends CborSerializable
  case class Start[E, R](transactionId: String,  sagaStep: SagaTransactionStep[E, R], replyTo: Option[ActorRef[StepResult[RetryableOrNotException, R]]]) extends Command
  case class RecoverExecution[E, R](transactionId: String, sagaStep: SagaTransactionStep[E, R], replyTo: Option[ActorRef[StepResult[RetryableOrNotException, R]]]) extends Command
   case class OperationResponse[R](result: Either[RetryableOrNotException, R], replyTo: Option[ActorRef[StepResult[RetryableOrNotException, R]]]) extends Command
   case class RetryOperation[E, R](replyTo: Option[ActorRef[StepResult[RetryableOrNotException, R]]]) extends Command
   case class TimedOut[E, R](replyTo: Option[ActorRef[StepResult[RetryableOrNotException, R]]]) extends Command
  sealed trait StepResult[E, R] extends CborSerializable
  case class StepCompleted[E,R](transactionId: String, result: R) extends StepResult[E, R]
  case class StepFailed[E, R](transactionId: String, error: E) extends StepResult[E, R]

  // Events
  sealed trait Event
  case class ExecutionStarted[E, R](transactionId: String, transactionStep: SagaTransactionStep[E, R]) extends Event
  case class OperationSucceeded(result: Any) extends Event
  case class OperationFailed(error: RetryableOrNotException) extends Event
  case class RetryScheduled(retryCount: Int) extends Event

  // State
  case class State[E, R](
                          step: Option[SagaTransactionStep[E, R]] = None,
                          transactionId: Option[String] = None,
                          status: Status = Created,
                          retries: Int = 0,
                          lastError: Option[RetryableOrNotException] = None,
                          circuitBreakerOpen: Boolean = false
                        ) extends CborSerializable

  sealed trait Status extends CborSerializable
  case object Created extends Status
  case object Ongoing extends Status
  case object Succeed extends Status
  case object Failed extends Status

  // Response
  sealed trait Response extends CborSerializable
  case class OperationSuccess(result: Any) extends Response
  case class OperationFailure(error: RetryableOrNotException) extends Response

  // @formatter:on

  private def onRecoveryCompleted[E, R](context: ActorContext[Command], state: State[E, R]): Unit = {
    context.log.info(s"SAGA Transaction Step is Recovered on ${state}")
    state.status match {
      case Created | Succeed =>
        state.step.foreach { step =>
          context.log.info(s"TrxId: ${state.transactionId} Phase: ${step.phase} StepKey: ${step.stepId} | No recovery action for durability on status: ${state.status}")
        }
        ()
      case Failed =>
        state.lastError.zip(state.step).foreach { case (error, step) => error match {

          case RetryableFailure(msg) if state.retries < step.maxRetries =>
            context.log.info(s"TrxId: ${state.transactionId} Phase: ${step.phase} StepKey: ${step.stepId} | Take RetryOperation on RetryableFailure(${msg})")
            context.self ! RetryOperation(None)
          case RetryableFailure(msg) =>
            context.log.info(s"TrxId: ${state.transactionId} Phase: ${step.phase} StepKey: ${step.stepId} | No RetryOperation needed on RetryableFailure(${msg}) since max retries reached.")
            ()
          case NonRetryableFailure(msg) =>
            context.log.info(s"TrxId: ${state.transactionId} Phase: ${step.phase} StepKey: ${step.stepId} | No RetryOperation needed on NonRetryableFailure(${msg}).")
            ()
        }
        }
      case Ongoing =>
        state.step.zip(state.transactionId).foreach { case (step, trxId) =>
          if (step.retryWhenRecoveredOngoing) {
            context.log.info(s"TrxId: ${state.transactionId} Phase: ${step.phase} StepKey: ${step.stepId} | Take RetryOperation on Ongoing state")

            context.self ! RecoverExecution(trxId, step, None)
          } else {
            context.log.info(s"TrxId: ${state.transactionId} Phase: ${step.phase} StepKey: ${step.stepId} | No need to take RetryOperation on Ongoing state while retryWhenRecoveredOngoing is ${step.retryWhenRecoveredOngoing}")
          }
        }
    }
  }

  def apply[E, R](
                   persistenceId: PersistenceId,
                   participant: SagaParticipant[E, R],
                   maxRetries: Int,
                   initialRetryDelay: FiniteDuration,
                   circuitBreakerSettings: CircuitBreakerSettings
                 ): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>

        val circuitBreaker: CircuitBreaker = CircuitBreaker(
          scheduler = context.system.classicSystem.scheduler,
          maxFailures = circuitBreakerSettings.maxFailures,
          callTimeout = circuitBreakerSettings.callTimeout,
          resetTimeout = circuitBreakerSettings.resetTimeout
        )

        EventSourcedBehavior[Command, Event, State[E, R]](
          persistenceId = persistenceId,
          emptyState = State[E, R](),
          commandHandler = commandHandler[E, R](context, timers, participant, maxRetries, initialRetryDelay, circuitBreaker),
          eventHandler = eventHandler[E, R]
        ).receiveSignal {
          case (state, RecoveryCompleted) =>
            onRecoveryCompleted[E, R](context, state)
        }
      }
    }
  }

  private def commandHandler[E, R](
                                    context: akka.actor.typed.scaladsl.ActorContext[Command],
                                    timers: TimerScheduler[Command],
                                    participant: SagaParticipant[E, R],
                                    maxRetries: Int,
                                    initialRetryDelay: FiniteDuration,
                                    circuitBreaker: CircuitBreaker
                                  ): (State[E, R], Command) => Effect[Event, State[E, R]] = { (state, command) =>
    command match {
      case Start(transactionId, step, replyTo: Some[ActorRef[StepResult[E, R]]]) if state.status == Created =>

        Effect
          .persist(ExecutionStarted(transactionId, step))
          .thenRun(_ => executeOperation(context, step.phase, step, transactionId, circuitBreaker, replyTo))

      case RecoverExecution(transactionId, step, replyTo) if state.status == Ongoing && state.step.exists(_.retryWhenRecoveredOngoing) =>

        Effect
          .persist(ExecutionStarted(transactionId, step))
          .thenRun(_ => executeOperation(context, step.phase, step, transactionId, circuitBreaker, replyTo))


      case OperationResponse(Right(result), replyTo) if state.status == Ongoing =>
        Effect
          .persist(OperationSucceeded(result))
          .thenRun(updatedState => updatedState.status match {
            // TODO ack coordinator with step result
            case Succeed => // Notify success
              replyTo.foreach(_ ! StepCompleted(state.transactionId.get, result))
            case _ => // Unexpected state
          })

      case OperationResponse(Left(error: RetryableFailure), replyTo)
        if (state.status == Ongoing || state.status == Failed)
          && state.retries < maxRetries =>

        val nextRetry = state.retries + 1
        val nextDelay = calculateBackoffDelay(initialRetryDelay, nextRetry)

        Effect
          .persist(List(OperationFailed(error), RetryScheduled(nextRetry)))
          .thenRun(_ => scheduleRetry(timers, nextDelay, replyTo))


      case TimedOut(replyTo) if state.status == Ongoing && state.retries < maxRetries =>
        context.log.warn(s"TimedOut found ${state.retries} times")
        val nextRetry = state.retries + 1
        val nextDelay = calculateBackoffDelay(initialRetryDelay, nextRetry)

        Effect
          .persist(List(OperationFailed(RetryableFailure("timed out")), RetryScheduled(nextRetry)))
          .thenRun(_ => scheduleRetry(timers, nextDelay, replyTo))


      case RetryOperation(replyTo: Option[ActorRef[StepResult[RetryableOrNotException, R]]]) if state.status == Ongoing =>
        state.step.zip(state.transactionId).map {
          case (step, trxId) =>
            Effect.none[Event, State[E, R]]
              .thenRun(_ => executeOperation[E, R](context, step.phase, step, state.transactionId.get, circuitBreaker, replyTo))
        }.getOrElse(Effect.none)

      case OperationResponse(Left(error), replyTo) =>
        // TODO ack coordinator with step result
        Effect.persist(OperationFailed(error))
          .thenRun(_ => replyTo.foreach(_ ! StepFailed(state.transactionId.get, error)))
      case TimedOut(replyTo) =>
        // TODO ack coordinator with step result
        Effect.persist(OperationFailed(RetryableFailure("timed out")))
          .thenRun(_ => replyTo.foreach(_ ! StepFailed(state.transactionId.get, RetryableFailure("timed out"))))
      case msg =>
        context.log.warn(s"msg: $msg is not processed")
        Effect.none
    }
  }

  private def eventHandler[E, R]: (State[E, R], Event) => State[E, R] = { (state, event) =>
    event match {
      case ExecutionStarted(transactionId, step) =>
        state.copy(transactionId = Some(transactionId), step = Some(step.asInstanceOf[SagaTransactionStep[E, R]]), status = Ongoing)
      case OperationSucceeded(_) =>
        state.copy(status = Succeed)
      case OperationFailed(error) =>
        state.copy(status = Failed, lastError = Some(error))
      case RetryScheduled(_) =>
        state.copy(retries = state.retries + 1, status = Ongoing)

    }
  }

  val logger = LoggerFactory.getLogger(getClass)

  private def executeOperation[E, R](
                                      context: akka.actor.typed.scaladsl.ActorContext[Command],
                                      stepPhase: TransactionPhase,
                                      step: SagaTransactionStep[E, R],
                                      transactionId: String,
                                      circuitBreaker: CircuitBreaker,
                                      replyTo: Option[ActorRef[StepResult[RetryableOrNotException, R]]]
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

  private def scheduleRetry[E, R](timers: TimerScheduler[Command], delay: FiniteDuration, replyTo: Option[ActorRef[StepResult[RetryableOrNotException, R]]]): Unit = {
    timers.startSingleTimer(RetryOperation(replyTo), delay)
  }

  private def calculateBackoffDelay(initialDelay: FiniteDuration, retryCount: Int): FiniteDuration = {
    initialDelay * math.pow(2, retryCount - 1).toLong
  }

  case class CircuitBreakerSettings(maxFailures: Int, callTimeout: FiniteDuration, resetTimeout: FiniteDuration)

}