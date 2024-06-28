package net.imadz.common.application.saga

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import net.imadz.common.application.saga.behaviors.{SagaCommandBehaviors, SagaEventHandler}

import scala.concurrent.{ExecutionContext, Future}


object TransactionCoordinator {
  // Any method of Participant should be idempotent
  trait Participant {
    def prepare(transactionId: String, context: Map[String, Any]): Future[Boolean]

    def commit(transactionId: String, context: Map[String, Any]): Future[Boolean]

    def rollback(transactionId: String, context: Map[String, Any]): Future[Boolean]
  }

  // @formatter:off
  // Value Object
  case class Transaction(id: String, steps: Seq[TransactionStep], phases: Seq[String])
  case class TransactionStep(id: String, phase: String, participant: Participant)

  // Commands
  sealed trait SagaCommand
  final case class StartTransaction(transaction: Transaction, replyTo: ActorRef[TransactionResponse]) extends SagaCommand
  case class StartNextStep(replyTo: Option[ActorRef[TransactionResponse]]) extends SagaCommand
  case class CompleteStep(step: TransactionStep, success: Boolean, replyTo: Option[ActorRef[TransactionResponse]]) extends SagaCommand
  case class CompletePhase(phase: String, success: Boolean, replyTo: Option[ActorRef[TransactionResponse]]) extends SagaCommand

  sealed trait TransactionResponse
  object TransactionResponse {
    case class Completed(transactionId: String) extends TransactionResponse
    case class Failed(transactionId: String, reason: String) extends TransactionResponse
  }
  // Events
  sealed trait SagaEvent
  final case class TransactionStarted(transaction: Transaction) extends SagaEvent
  final case class TransactionPhaseStarted(phase: String) extends SagaEvent
  final case class TransactionStepStarted(step: TransactionStep) extends SagaEvent
  final case class StepCompleted(step: TransactionStep, success: Boolean) extends SagaEvent
  final case class PhaseCompleted(phase: String, success: Boolean) extends SagaEvent
  final case class TransactionCompleted(success: Boolean) extends SagaEvent

  // State
  final case class State(
    currentTransaction: Option[Transaction] = None,
    completedSteps: Set[TransactionStep] = Set.empty,
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
        case "prepare" | "commit" | "rollback" => context.self ! StartNextStep(None)
        case _ => // Do nothing
      }
    }
  }
  // @formatter:off
  val entityTypeKey: EntityTypeKey[SagaCommand] = EntityTypeKey("SagaTransaction")
  val tags: Vector[String] = Vector.tabulate(5)(i => s"SagaTransaction-$i")
  // @formatter:on
}