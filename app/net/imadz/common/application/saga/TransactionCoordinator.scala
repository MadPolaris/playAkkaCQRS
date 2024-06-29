package net.imadz.common.application.saga

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import net.imadz.common.application.saga.behaviors.{SagaCommandBehaviors, SagaEventHandler}

import java.net.ConnectException
import java.sql.SQLTransientException
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future, TimeoutException}


object TransactionCoordinator {

  // Value Object
  sealed trait TransactionPhase {
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

  sealed trait RetryableOrNotException
  case class RetryableFailure(message: String) extends RuntimeException(message) with RetryableOrNotException
  case class NonRetryableFailure(message: String) extends RuntimeException(message) with RetryableOrNotException

  // Any method of Participant should be idempotent
  trait Participant {
    def prepare(transactionId: String, context: Map[String, Any]): Future[Boolean]

    def commit(transactionId: String, context: Map[String, Any]): Future[Boolean]

    def compensate(transactionId: String, context: Map[String, Any]): Future[Boolean]

    protected def executeWithRetryClassification[T](
      operation: => Future[T]
    )(implicit ec: ExecutionContext): Future[T] = {
      operation.recoverWith {
        case e: Exception => classifyFailure(e) match {
          case RetryableFailure(msg) => Future.failed(RetryableFailure(msg))
          case NonRetryableFailure(msg) => Future.failed(NonRetryableFailure(msg))
        }
      }
    }

    private def classifyFailure(e: Exception): RetryableOrNotException = defaultClassification
      .orElse(specificClassification)
      .apply(e)

    protected def defaultClassification: PartialFunction[Exception, RetryableOrNotException] = {
      case _: TimeoutException => RetryableFailure("Operation timed out")
      case _: ConnectException => RetryableFailure("Connection failed")
      case _: SQLTransientException => RetryableFailure("Transient database error")
      case _: IllegalArgumentException => NonRetryableFailure("Invalid argument")
    }

    protected def specificClassification: PartialFunction[Exception, RetryableOrNotException] = {
      case e => NonRetryableFailure("Unclassified error: " + e.getMessage)
    }
  }

  // @formatter:off
  sealed trait StepStatus
  case object StepCreated extends StepStatus
  case object StepOngoing extends StepStatus
  case object StepCompleted extends StepStatus
  case object StepFailed extends StepStatus
  case object StepTimedOut extends StepStatus
  case object StepCompensated extends StepStatus

  case class Transaction(
    id: String,
    steps: Seq[TransactionStep],
    phases: Seq[String])

  case class TransactionStep(
    id: String,
    phase: String,
    participant: Participant,
    status: StepStatus = StepCreated,
    failedReason: Option[String] = None,
    retries: Int = 0,
    timeoutDuration: FiniteDuration = 30.seconds
  )

  // Commands
  sealed trait SagaCommand
  final case class StartTransaction(transaction: Transaction, replyTo: ActorRef[TransactionResponse]) extends SagaCommand
  case class StartNextStep(replyTo: Option[ActorRef[TransactionResponse]]) extends SagaCommand
  case class CompleteStep(step: TransactionStep, success: Boolean, replyTo: Option[ActorRef[TransactionResponse]]) extends SagaCommand
  case class CompletePhase(phase: String, success: Boolean, replyTo: Option[ActorRef[TransactionResponse]]) extends SagaCommand
  case class ExecuteStep(step: TransactionStep, replyTo: Option[ActorRef[TransactionResponse]]) extends SagaCommand
  case class RetryStep(step: TransactionStep, replyTo: Option[ActorRef[TransactionResponse]]) extends SagaCommand
  case class StepTimeout(step: TransactionStep, replyTo: Option[ActorRef[TransactionResponse]]) extends SagaCommand
  case class CompensateStep(step: TransactionStep, replyTo: Option[ActorRef[TransactionResponse]]) extends SagaCommand

  sealed trait TransactionResponse
  object TransactionResponse {
    case class Completed(transactionId: String) extends TransactionResponse
    case class Failed(transactionId: String, reason: String) extends TransactionResponse
    case class PartiallyCompleted(transactionId: String, completedSteps: Seq[TransactionStep], failedSteps: Seq[TransactionStep]) extends TransactionResponse

  }
  // Events
  sealed trait SagaEvent
  final case class TransactionStarted(transaction: Transaction) extends SagaEvent
  final case class TransactionPhaseStarted(phase: String) extends SagaEvent
  final case class TransactionStepStarted(step: TransactionStep) extends SagaEvent
  final case class StepCompleted(step: TransactionStep, success: Boolean) extends SagaEvent
  final case class StepFailed(step: TransactionStep, reason: String) extends SagaEvent
  final case class StepTimedOut(step: TransactionStep) extends SagaEvent
  final case class StepCompensated(step: TransactionStep) extends SagaEvent
  final case class PhaseCompleted(phase: String, success: Boolean) extends SagaEvent
  final case class TransactionCompleted(success: Boolean) extends SagaEvent

  // State
  final case class State(
    currentTransaction: Option[Transaction] = None,
    completedSteps: Set[TransactionStep] = Set.empty,
    failedSteps: Set[TransactionStep] = Set.empty,
    compensatedSteps: Set[TransactionStep] = Set.empty,
    currentPhase: String = "",
    currentStep: Option[TransactionStep] = None
  )
  // @formatter:on

  def apply(persistenceId: PersistenceId)(implicit ec: ExecutionContext): Behavior[SagaCommand] = Behaviors.setup { context =>
    EventSourcedBehavior[SagaCommand, SagaEvent, State](
      persistenceId = persistenceId,
      emptyState = State(),
      commandHandler = commandHandler(context),
      eventHandler = eventHandler
    ).receiveSignal {
      case (state, RecoveryCompleted) => onRecoveryCompleted(context)(state)
    }
  }

  def commandHandler(context: ActorContext[SagaCommand])(implicit ec: ExecutionContext): (State, SagaCommand) => Effect[SagaEvent, State] = SagaCommandBehaviors.commandHandler(context)

  val eventHandler: (State, SagaEvent) => State = SagaEventHandler.eventHandler

  private def onRecoveryCompleted(context: ActorContext[SagaCommand])(state: State): Unit = {
    state.currentTransaction.foreach { _ =>
      state.currentPhase match {
        case PreparePhase.key | CommitPhase.key | CompensatePhase.key => context.self ! StartNextStep(None)
        case _ => // Do nothing
      }
    }
  }

  // @formatter:off
  val entityTypeKey: EntityTypeKey[SagaCommand] = EntityTypeKey("SagaTransaction")
  val tags: Vector[String] = Vector.tabulate(5)(i => s"SagaTransaction-$i")
  // @formatter:on
}