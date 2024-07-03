package net.imadz.infra.saga
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, Scheduler}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.util.Timeout
import net.imadz.common.CborSerializable
import net.imadz.infra.saga.SagaParticipant.{NonRetryableFailure, RetryableOrNotException}
import net.imadz.infra.saga.SagaPhase._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

object SagaTransactionCoordinator {


  // Commands
  sealed trait Command extends CborSerializable
  case class StartTransaction(transactionId: String, steps: List[SagaTransactionStep[_, _]]) extends Command
  private case class PhaseCompleted(phase: TransactionPhase, results: List[Either[RetryableOrNotException, Any]]) extends Command
  private case class PhaseFailed(phase: TransactionPhase, error: RetryableOrNotException) extends Command

  // Events
  sealed trait Event
  case class TransactionStarted(transactionId: String, steps: List[SagaTransactionStep[_, _]]) extends Event
  case class PhaseSucceeded(phase: TransactionPhase) extends Event
  case class TransactionCompleted(transactionId: String) extends Event
  case class TransactionFailed(transactionId: String, reason: String) extends Event

  // State
  case class State(
                    transactionId: Option[String] = None,
                    steps: List[SagaTransactionStep[_, _]] = List.empty,
                    currentPhase: TransactionPhase = PreparePhase,
                    status: Status = Created
                  )

  sealed trait Status
  case object Created extends Status
  case object InProgress extends Status
  case object Completed extends Status
  case object Failed extends Status

  def apply(
             persistenceId: PersistenceId,
             stepExecutorFactory: (String, SagaTransactionStep[_, _]) => ActorRef[StepExecutor.Command]
           )(implicit ec: ExecutionContext, timeout: Timeout): Behavior[Command] = Behaviors.setup { context =>
    EventSourcedBehavior[Command, Event, State](
      persistenceId = persistenceId,
      emptyState = State(),
      commandHandler = commandHandler(context, stepExecutorFactory),
      eventHandler = eventHandler
    )
  }

  private def commandHandler(
                              context: ActorContext[Command],
                              stepExecutorFactory: (String, SagaTransactionStep[_, _]) => ActorRef[StepExecutor.Command]
                            )(implicit ec: ExecutionContext, timeout: Timeout): (State, Command) => Effect[Event, State] = { (state, command) =>
    command match {
      case StartTransaction(transactionId, steps) if state.status == Created =>
        Effect
          .persist(TransactionStarted(transactionId, steps))
          .thenRun { _ =>
            executePhase(context, State(Some(transactionId), steps, PreparePhase, InProgress), stepExecutorFactory)
          }

      case PhaseCompleted(phase, results) =>
        handlePhaseCompletion(context, state, phase, results, stepExecutorFactory)

      case PhaseFailed(phase, error) =>
        handlePhaseFailure(context, state, phase, error, stepExecutorFactory)

      case _ => Effect.none
    }
  }

  private def handlePhaseCompletion(
                                     context: ActorContext[Command],
                                     state: State,
                                     phase: TransactionPhase,
                                     results: List[Either[RetryableOrNotException, Any]],
                                     stepExecutorFactory: (String, SagaTransactionStep[_, _]) => ActorRef[StepExecutor.Command]
                                   )(implicit ec: ExecutionContext, timeout: Timeout): Effect[Event, State] = {
    if (results.forall(_.isRight)) {
      phase match {
        case PreparePhase =>
          Effect
            .persist(PhaseSucceeded(PreparePhase))
            .thenRun { _ => executePhase(context, state.copy(currentPhase = CommitPhase), stepExecutorFactory) }
        case CommitPhase =>
          Effect.persist(
            List(
              PhaseSucceeded(CommitPhase),
              TransactionCompleted(state.transactionId.get)
            )
          )
        case CompensatePhase =>
          Effect.persist(
            List(
              PhaseSucceeded(CompensatePhase),
              TransactionFailed(state.transactionId.get, "Transaction compensated due to failure")
            )
          )
      }
    } else {
      // If any step in the phase failed, start compensation
      Effect
        .persist(TransactionFailed(state.transactionId.get, s"Phase $phase failed"))
        .thenRun { _ => executePhase(context, state.copy(currentPhase = CompensatePhase), stepExecutorFactory) }
    }
  }

  private def handlePhaseFailure(
                                  context: ActorContext[Command],
                                  state: State,
                                  phase: TransactionPhase,
                                  error: RetryableOrNotException,
                                  stepExecutorFactory: (String, SagaTransactionStep[_, _]) => ActorRef[StepExecutor.Command]
                                )(implicit ec: ExecutionContext, timeout: Timeout): Effect[Event, State] = {
    Effect
      .persist(TransactionFailed(state.transactionId.get, s"Phase $phase failed with error: ${error.message}"))
      .thenRun { _ =>
        if (phase != CompensatePhase) {
          executePhase(context, state.copy(currentPhase = CompensatePhase), stepExecutorFactory)
        }
      }
  }

  private def executePhase(
                            context: ActorContext[Command],
                            state: State,
                            stepExecutorFactory: (String, SagaTransactionStep[_, _]) => ActorRef[StepExecutor.Command]
                          )(implicit ec: ExecutionContext, askTimeout: Timeout): Unit = {

    val stepsInPhase = state.steps.filter(_.phase == state.currentPhase)

    import akka.actor.typed.scaladsl.AskPattern._
    implicit val scheduler: Scheduler = context.system.scheduler

    val futureResults = stepsInPhase.map { step =>
      val stepExecutor = stepExecutorFactory(
        s"${state.transactionId.get}-${step.stepId}-${state.currentPhase}",
        step
      )
      stepExecutor.ask((ref: ActorRef[StepExecutor.StepResult]) => StepExecutor.Start(state.transactionId.get, step, Some(ref)))(askTimeout, scheduler).map {
        case StepExecutor.StepCompleted(_, result) => result
        case StepExecutor.StepFailed(_, error) => Left(error)
      }
    }
    implicit val timeout: Timeout = 30.seconds // Adjust as needed

    import scala.concurrent.Future
    Future.sequence(futureResults).onComplete {
      case Success(results) => context.self ! PhaseCompleted(state.currentPhase, results.map(Right.apply))
      case Failure(error) => context.self ! PhaseFailed(state.currentPhase, NonRetryableFailure(error.getMessage))
    }
  }

  private def eventHandler: (State, Event) => State = { (state, event) =>
    event match {
      case TransactionStarted(transactionId, steps) =>
        state.copy(transactionId = Some(transactionId), steps = steps, status = InProgress)
      case PhaseSucceeded(phase) =>
        phase match {
          case PreparePhase => state.copy(currentPhase = CommitPhase)
          case CommitPhase => state.copy(status = Completed)
          case CompensatePhase => state.copy(status = Failed)
        }
      case TransactionCompleted(_) =>
        state.copy(status = Completed)
      case TransactionFailed(_, _) =>
        state.copy(status = Failed)
    }
  }
}