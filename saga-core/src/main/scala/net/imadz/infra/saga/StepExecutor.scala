package net.imadz.infra.saga

import akka.actor.ExtendedActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.pattern.CircuitBreaker
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import net.imadz.common.CborSerializable
import net.imadz.infra.saga.SagaParticipant.{RetryableOrNotException, SagaResult}
import net.imadz.infra.saga.handlers.{StepExecutorCommandHandler, StepExecutorEventHandler, StepExecutorRecoveryHandler}
import net.imadz.infra.saga.persistence.StepExecutorEventAdapter

import scala.concurrent.duration._

/**
 * Executes a single step of a Saga.
 * It manages retries, timeouts, and circuit breaking for the step execution.
 */
object StepExecutor {
  // @formatter:off
  /**
   * Configuration for the circuit breaker used in step execution.
   */
  case class CircuitBreakerSettings(maxFailures: Int, callTimeout: FiniteDuration, resetTimeout: FiniteDuration)

  /**
   * The commands that the StepExecutor can process.
   */
  sealed trait Command extends CborSerializable
  case class Start[E, R, C](transactionId: String,  sagaStep: SagaTransactionStep[E, R, C], replyTo: Option[ActorRef[StepResult[E, R, C]]], traceId: String) extends Command
  case class RecoverExecution[E, R, C](transactionId: String, sagaStep: SagaTransactionStep[E, R, C], replyTo: Option[ActorRef[StepResult[E, R, C]]], traceId: String) extends Command
  case class OperationResponse[E, R, C](result: Either[RetryableOrNotException, SagaResult[R]], replyTo: Option[ActorRef[StepResult[E, R, C]]]) extends Command
  case class RetryOperation[E, R, C](replyTo: Option[ActorRef[StepResult[E, R, C]]]) extends Command
  case class TimedOut[E, R, C](replyTo: Option[ActorRef[StepResult[E, R, C]]]) extends Command
  case class QueryStatus[E, R, C](replyTo: ActorRef[State[E, R, C]]) extends Command
  case class ManualFix[E, R, C](replyTo: Option[ActorRef[StepResult[E, R, C]]]) extends Command

  /**
   * The results returned by the StepExecutor.
   */
  sealed trait StepResult[E, R, C] extends CborSerializable
  case class StepCompleted[E,R, C](transactionId: String, stepId: String, result: SagaResult[R]) extends StepResult[E, R, C]
  case class StepFailed[E, R, C](transactionId: String, stepId: String, error: RetryableOrNotException) extends StepResult[E, R, C]

  /**
   * Events persisted by the StepExecutor.
   */
  sealed trait Event {
    def transactionId: String
    def stepId: String
    def phase: String
    def traceId: String
  }
  case class ExecutionStarted[E, R, C](transactionId: String, transactionStep: SagaTransactionStep[E, R, C], replyToPath: String, traceId: String) extends Event {
    override def stepId: String = transactionStep.stepId
    override def phase: String = transactionStep.phase.toString
  }
  case class OperationSucceeded[R](transactionId: String, stepId: String, phase: String, traceId: String, result: SagaResult[R]) extends Event
  case class ManualFixCompleted[R](transactionId: String, stepId: String, phase: String, traceId: String, result: SagaResult[R]) extends Event
  case class OperationFailed(transactionId: String, stepId: String, phase: String, traceId: String, error: RetryableOrNotException) extends Event
  case class RetryScheduled(transactionId: String, stepId: String, phase: String, traceId: String, retryCount: Int) extends Event

  /**
   * Represents the state of a single step execution.
   */
  case class State[E, R, C](
                          step: Option[SagaTransactionStep[E, R, C]] = None,
                          transactionId: Option[String] = None,
                          traceId: Option[String] = None,
                          status: Status = Created,
                          retries: Int = 0,
                          lastError: Option[RetryableOrNotException] = None,
                          result: Option[SagaResult[R]] = None,
                          circuitBreakerOpen: Boolean = false,
                          replyTo: Option[String] = None
                        ) extends CborSerializable {
    def canRetry: Boolean = this.status == Ongoing || this.status == Failed

    def canScheduleRetryOnTimedOut(defaultMaxRetries: Int): Boolean = this.status == Ongoing && !this.maxRetriesReached(defaultMaxRetries)

    def canScheduleRetryOnFailure(defaultMaxRetries: Int): Boolean = ((this.status == Ongoing || this.status == Failed)
      && !this.maxRetriesReached(defaultMaxRetries))

    def canStart: Boolean = this.status == Created

    def canRecover: Boolean = this.status == Ongoing && this.step.exists(_.retryWhenRecoveredOngoing)

    private def maxRetriesReached(defaultMaxRetries: Int): Boolean = {
      this.retries >= this.step.map(_.maxRetries).getOrElse(defaultMaxRetries)
    }
  }

  /**
   * The status of a step execution.
   */
  sealed trait Status extends CborSerializable
  case object Created extends Status
  case object Ongoing extends Status
  case object Succeed extends Status
  case object Failed extends Status

  // @formatter:on

  /**
   * Creates a new StepExecutor.
   *
   * @param persistenceId the unique persistence identifier
   * @param context the context provided to the saga participant
   * @param defaultMaxRetries default maximum number of retries if not specified in the step
   * @param initialRetryDelay initial delay for backoff retries
   * @param circuitBreakerSettings settings for the circuit breaker
   * @param extendedSystem the classic actor system
   * @return the actor behavior
   */
  def apply[E, R, C](
                      persistenceId: PersistenceId,
                      context: C,
                      defaultMaxRetries: Int,
                      initialRetryDelay: FiniteDuration,
                      circuitBreakerSettings: CircuitBreakerSettings,
                      extendedSystem: ExtendedActorSystem): Behavior[Command] = {
    Behaviors.setup { actorContext =>
      Behaviors.withTimers { timers =>

        val circuitBreaker: CircuitBreaker = CircuitBreaker(
          scheduler = actorContext.system.classicSystem.scheduler,
          maxFailures = circuitBreakerSettings.maxFailures,
          callTimeout = circuitBreakerSettings.callTimeout,
          resetTimeout = circuitBreakerSettings.resetTimeout
        )

        EventSourcedBehavior[Command, Event, State[E, R, C]](
          persistenceId = persistenceId,
          emptyState = State[E, R, C](),
          commandHandler = StepExecutorCommandHandler.commandHandler[E, R, C](actorContext, context, timers, defaultMaxRetries, initialRetryDelay, circuitBreaker),
          eventHandler = StepExecutorEventHandler.eventHandler[E, R, C]
        ).eventAdapter(new StepExecutorEventAdapter(extendedSystem)).receiveSignal {
          case (state, RecoveryCompleted) =>
            StepExecutorRecoveryHandler.onRecoveryCompleted[E, R, C](actorContext, state)
        }
      }
    }
  }
}
