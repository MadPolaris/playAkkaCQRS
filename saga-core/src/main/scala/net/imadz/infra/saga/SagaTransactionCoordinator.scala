package net.imadz.infra.saga

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import net.imadz.common.CborSerializable
import net.imadz.infra.saga.SagaParticipant.{RetryableFailure, RetryableOrNotException}
import net.imadz.infra.saga.SagaPhase._
import net.imadz.infra.saga.handlers.{SagaTransactionCoordinatorCommandHandler, SagaTransactionCoordinatorEventHandler, SagaTransactionCoordinatorRecoveryHandler}
import play.api.libs.json._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
 * The central orchestrator for a Saga transaction.
 * Manages the lifecycle of a Saga, including execution of steps in phases (Prepare, Commit, Compensate)
 * and handling failures and retries.
 */
object SagaTransactionCoordinator {
  val tags: Vector[String] = Vector.tabulate(5)(i => s"SagaTransactionCoordinator-$i")
  val entityTypeKey: EntityTypeKey[Command] = EntityTypeKey("SagaTransactionCoordinator")


  // @formatter:off
  /**
   * The commands that the SagaTransactionCoordinator can process.
   */
  sealed trait Command extends CborSerializable
  case class StartTransaction[E, R, C](transactionId: String, steps: List[SagaTransactionStep[E, R, C]], replyTo: Option[ActorRef[TransactionResult]], traceId: String = "", singleStep: Boolean = false, timeout: Option[FiniteDuration] = None) extends Command
  case object TransactionTimeout extends Command
  case class ProceedNext(replyTo: Option[ActorRef[TransactionResult]]) extends Command
  case class ResolveSuspended(replyTo: Option[ActorRef[TransactionResult]]) extends Command
  case class ManualFixStep(stepId: String, phase: TransactionPhase, replyTo: Option[ActorRef[TransactionResult]]) extends Command
  case class RetryCurrentPhase(replyTo: Option[ActorRef[TransactionResult]]) extends Command
  case class ProceedNextGroup(replyTo: Option[ActorRef[TransactionResult]]) extends Command
  case class TransactionPaused(transactionId: String, traceId: String) extends Command with Event
  case class TransactionResumed(transactionId: String, traceId: String) extends Command with Event
  case class PhaseCompleted(phase: TransactionPhase, results: List[Either[RetryableOrNotException, Any]], replyTo: Option[ActorRef[TransactionResult]]) extends Command
  case class PhaseFailure(phase: TransactionPhase, error: RetryableOrNotException, replyTo: Option[ActorRef[TransactionResult]]) extends Command
  case class InternalStepResult[E, R, C](result: StepExecutor.StepResult[E, R, C], replyTo: Option[ActorRef[TransactionResult]]) extends Command
  case class StartStepGroup(phase: TransactionPhase, group: Int, stepIds: Set[String], replyTo: Option[ActorRef[TransactionResult]]) extends Command
  
  /**
   * Represents a single step in the transaction tracing.
   */
  case class TracingStep(
                          stepNumber: Int,
                          stepId: String,
                          stepType: String,
                          phase: String,
                          participant: String,
                          status: String,
                          retries: Int,
                          maxRetries: Int,
                          timeoutInMillis: Long,
                          retryWhenRecoveredOngoing: Boolean,
                          circuitBreakerOpen: Boolean,
                          error: Option[ErrorInfo]
                        ) {
    override def toString: String = {
      val step = this
      s"""
         |Step Number: ${step.stepNumber}, Step Id: ${step.stepId}, Phase: ${step.phase}, Step Type: ${step.stepType},
         |Saga Participant: ${step.participant}, ${step.status},
         |Step Status: ${step.status}
         |Step Failure: ${step.error.map(e => "type: " + e.errorType +" , msg: " + e.message).getOrElse("")},
         |Retries/MaxRetries: ${step.retries}/${step.maxRetries},
         |RetryWhenRecoveredOngoing: ${step.retryWhenRecoveredOngoing},
         |Step Timeout: ${step.timeoutInMillis} millis,
         |CircuitBreakerOpen: ${step.circuitBreakerOpen}
         |
         |""".stripMargin.replaceAll("""\n""", "")
     }

  }
  object TracingStep {
    implicit val errorInfoFormat: OWrites[ErrorInfo] = Json.writes[ErrorInfo]

    implicit val tracingStepFormat: OWrites[TracingStep] = Json.writes[TracingStep]

    def fromStepExecutorState(state: StepExecutor.State[_, _, _], stepNumber: Int): TracingStep = {
      val step = state.step.getOrElse(throw new IllegalStateException(s"Step $stepNumber has no associated SagaTransactionStep"))
      TracingStep(
        stepNumber = stepNumber,
        stepId = step.stepId,
        stepType = step.getClass.getSimpleName,
        phase = step.phase.toString,
        participant = step.participant.getClass.getSimpleName,
        status = state.status.toString,
        retries = state.retries,
        maxRetries = step.maxRetries,
        timeoutInMillis = step.timeoutDuration.toMillis ,
        retryWhenRecoveredOngoing = step.retryWhenRecoveredOngoing,
        circuitBreakerOpen = state.circuitBreakerOpen,
        error = state.lastError.map(e => ErrorInfo(e.message, e.getClass.getSimpleName, e.isInstanceOf[RetryableFailure]))
      )
    }
  }

  /**
   * Error information for tracing.
   */
  case class ErrorInfo(
                        message: String,
                        errorType: String,
                        isRetryable: Boolean
                      )

  /**
   * The result of a transaction.
   */
  case class TransactionResult(successful: Boolean, state: State, failReason: String = "")

  /**
   * Events persisted by the SagaTransactionCoordinator.
   */
  sealed trait Event extends CborSerializable {
    def transactionId: String
  }
  case class TransactionStarted(transactionId: String, steps: List[SagaTransactionStep[_, _, _]], traceId: String = "", singleStep: Boolean = false, timeout: Option[FiniteDuration] = None) extends Event
  case class PhaseSucceeded(transactionId: String, phase: TransactionPhase) extends Event
  case class StepGroupSucceeded(transactionId: String, phase: TransactionPhase, group: Int) extends Event
  case class PhaseFailed(transactionId: String, phase: TransactionPhase) extends Event
  case class TransactionCompleted(transactionId: String) extends Event
  case class TransactionFailed(transactionId: String, reason: String) extends Event
  case class TransactionSuspended(transactionId: String, reason: String) extends Event
  case class TransactionResolved(transactionId: String) extends Event
  case class TransactionRetried(transactionId: String, phase: TransactionPhase) extends Event
  case class StepGroupStarted(transactionId: String, phase: TransactionPhase, group: Int, stepIds: Set[String]) extends Event
  case class StepResultReceived(transactionId: String, stepId: String, result: Either[RetryableOrNotException, Any]) extends Event

  /**
   * Represents the state of a Saga transaction.
   *
   * @param transactionId the unique identifier for the transaction
   * @param steps the list of steps in the saga
   * @param currentPhase the current phase of the transaction (Prepare, Commit, or Compensate)
   * @param status the overall status of the transaction
   * @param traceId an optional trace identifier for distributed tracing
   * @param singleStep if true, the transaction will pause after each step
   * @param isPaused if true, the transaction is currently paused
   * @param currentStepGroup the current group of steps being executed within the phase
   * @param pendingSteps the set of step IDs that are currently being executed
   * @param phaseResults the results accumulated during the current phase
   */
  case class State(
                    transactionId: Option[String] = None,
                    steps: List[SagaTransactionStep[_, _, _]] = List.empty,
                    currentPhase: TransactionPhase = PreparePhase,
                    status: Status = Created,
                    traceId: String = "",
                    singleStep: Boolean = false,
                    isPaused: Boolean = false,
                    currentStepGroup: Int = 1,
                    pendingSteps: Set[String] = Set.empty,
                    phaseResults: List[Either[RetryableOrNotException, Any]] = List.empty,
                    successfulSteps: Set[String] = Set.empty,
                    timeout: Option[FiniteDuration] = None
                    )
  /**
   * Overall status of the transaction.
   */
  sealed trait Status
  case object Created extends Status
  case object InProgress extends Status
  case object Completed extends Status
  case object Failed extends Status
  case object Compensating extends Status
  case object Suspended extends Status

  // @formatter:on

  case object TransactionTimeoutKey

  /**
   * Creates a new SagaTransactionCoordinator.
   *
   * @param persistenceId the unique persistence identifier
   * @param stepExecutorFactory a factory function to create StepExecutor actors
   * @param globalTimeout the global timeout for the entire transaction
   * @return the actor behavior
   */
  def apply(
             persistenceId: PersistenceId,
             stepExecutorFactory: (String, SagaTransactionStep[_, _, _]) => ActorRef[StepExecutor.Command],
             globalTimeout: FiniteDuration = 5.minutes
           )(implicit ec: ExecutionContext): Behavior[Command] = Behaviors.setup { context =>
    Behaviors.withTimers { timers =>
      EventSourcedBehavior[Command, Event, State](
        persistenceId = persistenceId,
        emptyState = State(),
        commandHandler = SagaTransactionCoordinatorCommandHandler.commandHandler(context, timers, stepExecutorFactory, globalTimeout),
        eventHandler = SagaTransactionCoordinatorEventHandler.eventHandler
      ).receiveSignal {
        case (state, akka.persistence.typed.RecoveryCompleted) =>
          SagaTransactionCoordinatorRecoveryHandler.onRecoveryCompleted(context, timers, state, stepExecutorFactory, globalTimeout)
      }
    }
  }
}
