package net.imadz.infra.saga

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.pattern.CircuitBreaker
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import net.imadz.common.CborSerializable
import net.imadz.infra.saga.SagaParticipant.RetryableOrNotException
import net.imadz.infra.saga.SagaPhase.TransactionPhase
import net.imadz.infra.saga.handlers.{StepExecutorCommandHandler, StepExecutorEventHandler, StepExecutorRecoveryHandler}

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
  // Value Class
  case class CircuitBreakerSettings(maxFailures: Int, callTimeout: FiniteDuration, resetTimeout: FiniteDuration)

  // Command
  sealed trait Command extends CborSerializable
  case class Start[E, R](transactionId: String,  sagaStep: SagaTransactionStep[E, R], replyTo: Option[ActorRef[StepResult[E, R]]]) extends Command
  case class RecoverExecution[E, R](transactionId: String, sagaStep: SagaTransactionStep[E, R], replyTo: Option[ActorRef[StepResult[E, R]]]) extends Command
   case class OperationResponse[E, R](result: Either[RetryableOrNotException, R], replyTo: Option[ActorRef[StepResult[E, R]]]) extends Command
   case class RetryOperation[E, R](replyTo: Option[ActorRef[StepResult[E, R]]]) extends Command
   case class TimedOut[E, R](replyTo: Option[ActorRef[StepResult[E, R]]]) extends Command
  sealed trait StepResult[E, R] extends CborSerializable
  case class StepCompleted[E,R](transactionId: String, result: R, state: State[E, R]) extends StepResult[E, R]
  case class StepFailed[E, R](transactionId: String, error: E, state: State[E, R]) extends StepResult[E, R]

  // Events
  sealed trait Event
  case class ExecutionStarted[E, R](transactionId: String, transactionStep: SagaTransactionStep[E, R], replyToPath: String) extends Event
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
                          circuitBreakerOpen: Boolean = false,
                          replyTo: Option[String] = None
                        ) extends CborSerializable {
    def canRetry: Boolean = this.status == Ongoing

    def canScheduleRetryOnTimedOut(defaultMaxRetries: Int): Boolean = this.status == Ongoing && this.maxRetriesReached(defaultMaxRetries)

    def canScheduleRetryOnFailure(defaultMaxRetries: Int): Boolean = ((this.status == Ongoing || this.status == Failed)
      && !this.maxRetriesReached(defaultMaxRetries))

    def canStart: Boolean = this.status == Created

    def canRecover: Boolean = this.status == Ongoing && this.step.exists(_.retryWhenRecoveredOngoing)

    private def maxRetriesReached(defaultMaxRetries: Int): Boolean = {
      this.retries >= this.step.map(_.maxRetries).getOrElse(defaultMaxRetries)
    }
  }

  sealed trait Status extends CborSerializable
  case object Created extends Status
  case object Ongoing extends Status
  case object Succeed extends Status
  case object Failed extends Status

  // @formatter:on


  def apply[E, R](persistenceId: PersistenceId, defaultMaxRetries: Int, initialRetryDelay: FiniteDuration, circuitBreakerSettings: CircuitBreakerSettings): Behavior[Command] = {
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
          commandHandler = StepExecutorCommandHandler.commandHandler[E, R](context, timers, defaultMaxRetries, initialRetryDelay, circuitBreaker),
          eventHandler = StepExecutorEventHandler.eventHandler[E, R]
        ).receiveSignal {
          case (state, RecoveryCompleted) =>
            StepExecutorRecoveryHandler.onRecoveryCompleted[E, R](context, state)
        }
      }
    }
  }


}