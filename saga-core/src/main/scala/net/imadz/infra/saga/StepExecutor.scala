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
import net.imadz.infra.saga.handlers.{StepExecutorCommandHandler, StepExecutorEventHandler}
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
                                         maxRetries: Int = 3,
                                         timeoutDuration: FiniteDuration = 5.seconds,
                                         retryWhenRecoveredOngoing: Boolean = false,
                                         stepGroup: Int = 1,
                                         circuitBreaker: Option[StepExecutor.CircuitBreakerSettings] = None
                                       )

/** Static definition data of one step — the ONLY step shape that is ever persisted.
  * Participants are pure functions of (definition, args) and never enter the journal. */
final case class StepDescriptor(
                                 stepId: String,
                                 phase: TransactionPhase,
                                 participantName: String,
                                 stepGroup: Int,
                                 maxRetries: Int,
                                 timeoutDuration: FiniteDuration,
                                 retryWhenRecoveredOngoing: Boolean,
                                 circuitBreaker: Option[StepExecutor.CircuitBreakerSettings] = None
                               )

object StepDescriptor {
  def of(step: SagaTransactionStep[_, _, _]): StepDescriptor =
    StepDescriptor(
      stepId = step.stepId,
      phase = step.phase,
      participantName = step.participant.getClass.getSimpleName,
      stepGroup = step.stepGroup,
      maxRetries = step.maxRetries,
      timeoutDuration = step.timeoutDuration,
      retryWhenRecoveredOngoing = step.retryWhenRecoveredOngoing,
      circuitBreaker = step.circuitBreaker
    )
}

object StepExecutor {
  // @formatter:off
  // Value Class
  case class CircuitBreakerSettings(maxFailures: Int, callTimeout: FiniteDuration, resetTimeout: FiniteDuration)

  // Command
  sealed trait Command extends CborSerializable
  /**
   * The single re-drive entry point (also the ONLY recovery trigger — the executor-side
   * RecoveryHandler never self-executes). Depending on the replayed status:
   *   Created            -> start the operation
   *   Ongoing            -> recover inline when the step's policy allows, else fail the step
   *   Succeed / Failed   -> cached reply, no re-execution
   */
  case class Attach[E, R, C](transactionId: String, sagaStep: SagaTransactionStep[E, R, C], replyTo: Option[ActorRef[StepResult[E, R, C]]], traceId: String) extends Command
  /** attempt = generation of the in-flight operation (retries + 1 at dispatch); stale responses are dropped. */
  case class OperationResponse[E, R, C](result: Either[RetryableOrNotException, SagaResult[R]], attempt: Int, replyTo: Option[ActorRef[StepResult[E, R, C]]]) extends Command
  case class RetryOperation[E, R, C](attempt: Int, replyTo: Option[ActorRef[StepResult[E, R, C]]]) extends Command
  case class TimedOut[E, R, C](attempt: Int, replyTo: Option[ActorRef[StepResult[E, R, C]]]) extends Command
  case class QueryStatus[E, R, C](replyTo: ActorRef[State[E, R, C]]) extends Command
  case class ManualFix[E, R, C](replyTo: Option[ActorRef[StepResult[E, R, C]]]) extends Command
  sealed trait StepResult[E, R, C] extends CborSerializable
  case class StepCompleted[E,R, C](transactionId: String, stepId: String, result: SagaResult[R]) extends StepResult[E, R, C]
  case class StepFailed[E, R, C](transactionId: String, stepId: String, error: RetryableOrNotException) extends StepResult[E, R, C]

  // Events — static definition data only, no participant payload
  sealed trait Event extends CborSerializable
  case class ExecutionStarted(transactionId: String, step: StepDescriptor, replyToPath: String, traceId: String) extends Event
  case class OperationSucceeded[R](result: SagaResult[R]) extends Event
  case class ManualFixCompleted[R](result: SagaResult[R]) extends Event
  case class OperationFailed(error: RetryableOrNotException) extends Event
  case class RetryScheduled(retryCount: Int) extends Event

  // State
  case class State[E, R, C](
                          stepDescriptor: Option[StepDescriptor] = None,
                          transactionId: Option[String] = None,
                          traceId: Option[String] = None,
                          status: Status = Created,
                          retries: Int = 0,
                          lastError: Option[RetryableOrNotException] = None,
                          result: Option[SagaResult[R]] = None,
                          circuitBreakerOpen: Boolean = false,
                          replyTo: Option[String] = None
                        ) extends CborSerializable {
    def canRetry: Boolean = this.status == Ongoing

    def canScheduleRetryOnTimedOut(defaultMaxRetries: Int): Boolean = this.status == Ongoing && !this.maxRetriesReached(defaultMaxRetries)

    def canScheduleRetryOnFailure(defaultMaxRetries: Int): Boolean = ((this.status == Ongoing || this.status == Failed)
      && !this.maxRetriesReached(defaultMaxRetries))

    def canStart: Boolean = this.status == Created

    private def maxRetriesReached(defaultMaxRetries: Int): Boolean = {
      this.retries > this.stepDescriptor.map(_.maxRetries).getOrElse(defaultMaxRetries)
    }
  }

  sealed trait Status extends CborSerializable
  case object Created extends Status
  case object Ongoing extends Status
  case object Succeed extends Status
  case object Failed extends Status

  // @formatter:on

  /** Holder for the live (participant-carrying) step of the current incarnation.
    * The journal only replays static descriptors; the participant arrives with Attach
    * and is kept here so timer-driven retries in the same incarnation can re-execute. */
  final class LiveStepHolder[E, R, C](val classicScheduler: akka.actor.Scheduler, initialBreaker: CircuitBreaker) {
    var current: Option[SagaTransactionStep[E, R, C]] = None
    var breaker: CircuitBreaker = initialBreaker
  }

  def apply[E, R, C](
                      persistenceId: PersistenceId,
                      context: C,
                      defaultMaxRetries: Int,
                      initialRetryDelay: FiniteDuration,
                      circuitBreakerSettings: CircuitBreakerSettings,
                      extendedSystem: ExtendedActorSystem): Behavior[Command] = {
    Behaviors.setup { actorContext =>
      Behaviors.withTimers { timers =>
        val defaultBreaker: CircuitBreaker = CircuitBreaker(
          scheduler = actorContext.system.classicSystem.scheduler,
          maxFailures = circuitBreakerSettings.maxFailures,
          callTimeout = circuitBreakerSettings.callTimeout,
          resetTimeout = circuitBreakerSettings.resetTimeout
        )
        val liveStep = new LiveStepHolder[E, R, C](actorContext.system.classicSystem.scheduler, defaultBreaker)

        EventSourcedBehavior[Command, Event, State[E, R, C]](
          persistenceId = persistenceId,
          emptyState = State[E, R, C](),
          commandHandler = StepExecutorCommandHandler.commandHandler[E, R, C](actorContext, context, timers, defaultMaxRetries, initialRetryDelay, defaultBreaker, liveStep),
          eventHandler = StepExecutorEventHandler.eventHandler[E, R, C]
        ).eventAdapter(new StepExecutorEventAdapter(extendedSystem))
          .receiveSignal {
            case (state, RecoveryCompleted) =>
              // Recovery is coordinator-driven: the executor never self-executes.
              // (Attach is the sole re-drive entry point, sent by the coordinator.)
              actorContext.log.debug(
                s"StepExecutor recovered: trxId=${state.transactionId.getOrElse("-")} status=${state.status} " +
                  s"retries=${state.retries} — waiting for Attach from the coordinator")
          }
      }
    }
  }
}
