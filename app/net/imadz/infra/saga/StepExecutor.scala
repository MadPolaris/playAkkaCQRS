package net.imadz.infra.saga

import akka.actor.ExtendedActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.pattern.CircuitBreaker
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import net.imadz.common.CborSerializable
import net.imadz.infra.saga.SagaParticipant.{RetryableOrNotException, SagaResult}
import net.imadz.infra.saga.SagaPhase.TransactionPhase
import net.imadz.infra.saga.handlers.{StepExecutorCommandHandler, StepExecutorEventHandler, StepExecutorRecoveryHandler}
import net.imadz.infra.saga.persistence.StepExecutorEventAdapter

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

case class SagaTransactionStep[E, R, C](
                                         stepId: String,
                                         phase: TransactionPhase,
                                         participant: SagaParticipant[E, R, C],
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
  case class Start[E, R, C](transactionId: String,  sagaStep: SagaTransactionStep[E, R, C], replyTo: Option[ActorRef[StepResult[E, R, C]]]) extends Command
  case class RecoverExecution[E, R, C](transactionId: String, sagaStep: SagaTransactionStep[E, R, C], replyTo: Option[ActorRef[StepResult[E, R, C]]]) extends Command
   case class OperationResponse[E, R, C](result: Either[RetryableOrNotException, R], replyTo: Option[ActorRef[StepResult[E, R, C]]]) extends Command
   case class RetryOperation[E, R, C](replyTo: Option[ActorRef[StepResult[E, R, C]]]) extends Command
   case class TimedOut[E, R, C](replyTo: Option[ActorRef[StepResult[E, R, C]]]) extends Command
  sealed trait StepResult[E, R, C] extends CborSerializable
  case class StepCompleted[E,R, C](transactionId: String, result: SagaResult[R], state: State[E, R, C]) extends StepResult[E, R, C]
  case class StepFailed[E, R, C](transactionId: String, error: E, state: State[E, R, C]) extends StepResult[E, R, C]

  // Events
  sealed trait Event
  case class ExecutionStarted[E, R, C](transactionId: String, transactionStep: SagaTransactionStep[E, R, C], replyToPath: String) extends Event
  case class OperationSucceeded[R](result: R) extends Event
  case class OperationFailed(error: RetryableOrNotException) extends Event
  case class RetryScheduled(retryCount: Int) extends Event

  // State
  case class State[E, R, C](
                          step: Option[SagaTransactionStep[E, R, C]] = None,
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