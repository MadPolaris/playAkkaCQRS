package net.imadz.infra.saga

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, Scheduler}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.util.Timeout
import net.imadz.common.CborSerializable
import net.imadz.infra.saga.SagaParticipant.{NonRetryableFailure, RetryableOrNotException}
import net.imadz.infra.saga.SagaPhase._
import net.imadz.infra.saga.StepExecutor.{StepCompleted, StepFailed, StepResult}

import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

object SagaTransactionCoordinator {


  // @formatter:off
  // Commands
  sealed trait Command extends CborSerializable
  case class StartTransaction(transactionId: String, steps: List[SagaTransactionStep[_, _]], replyTo: Option[ActorRef[TransactionResult]]) extends Command
  private case class PhaseCompleted(phase: TransactionPhase, results: List[Either[RetryableOrNotException, Any]], stepTraces: List[StepExecutor.State[_, _]], replyTo: Option[ActorRef[TransactionResult]]) extends Command
  private case class PhaseFailed(phase: TransactionPhase, error: RetryableOrNotException, stepTraces: List[StepExecutor.State[_, _]], replyTo: Option[ActorRef[TransactionResult]]) extends Command
  case class TransactionResult(successful: Boolean, state: State, stepTraces: List[StepExecutor.State[_, _]])

  // Events
  sealed trait Event extends CborSerializable
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

  // @formatter:on

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
      case StartTransaction(transactionId, steps, replyTo) if state.status == Created =>
        Effect
          .persist(TransactionStarted(transactionId, steps))
          .thenRun { _ =>
            executePhase(context, State(Some(transactionId), steps, PreparePhase, InProgress), stepExecutorFactory, Nil, replyTo)
          }

      case PhaseCompleted(phase, results, trace, replyTo) =>
        handlePhaseCompletion(context, state, phase, results, trace, stepExecutorFactory, replyTo)

      case PhaseFailed(phase, error, trace, replyTo) =>
        handlePhaseFailure(context, state, phase, error, trace, stepExecutorFactory, replyTo)

      case _ => Effect.none
    }
  }

  private def handlePhaseCompletion(
                                     context: ActorContext[Command],
                                     state: State,
                                     phase: TransactionPhase,
                                     results: List[Either[RetryableOrNotException, Any]],
                                     trace: List[StepExecutor.State[_, _]],
                                     stepExecutorFactory: (String, SagaTransactionStep[_, _]) => ActorRef[StepExecutor.Command],
                                     replyTo: Option[ActorRef[TransactionResult]]
                                   )(implicit ec: ExecutionContext, timeout: Timeout): Effect[Event, State] = {
    if (results.forall(_.isRight)) {
      phase match {
        case PreparePhase =>
          Effect
            .persist(PhaseSucceeded(PreparePhase))
            .thenRun { _ => executePhase(context, state.copy(currentPhase = CommitPhase), stepExecutorFactory, trace, replyTo) }
        case CommitPhase =>
          Effect.persist(
            List(
              PhaseSucceeded(CommitPhase),
              TransactionCompleted(state.transactionId.get)
            )
          ).thenRun(stateNew => replyTo.foreach(_ ! TransactionResult(successful = true, stateNew, trace)))
        case CompensatePhase =>
          Effect.persist(
            List(
              PhaseSucceeded(CompensatePhase),
              TransactionFailed(state.transactionId.get, "transaction failed but compensated")
            )
          ).thenRun(stateNew => replyTo.foreach(_ ! TransactionResult(successful = false, stateNew, trace)))
      }
    } else {
      // If any step in the phase failed, start compensation
      Effect
        .persist(TransactionFailed(state.transactionId.get, s"Phase $phase failed"))
        .thenRun { _ => executePhase(context, state.copy(currentPhase = CompensatePhase), stepExecutorFactory, trace, replyTo) }
    }
  }

  private def handlePhaseFailure(
                                  context: ActorContext[Command],
                                  state: State,
                                  phase: TransactionPhase,
                                  error: RetryableOrNotException,
                                  trace: List[StepExecutor.State[_, _]],
                                  stepExecutorFactory: (String, SagaTransactionStep[_, _]) => ActorRef[StepExecutor.Command],
                                  replyTo: Option[ActorRef[TransactionResult]]
                                )(implicit ec: ExecutionContext, timeout: Timeout): Effect[Event, State] = {
    Effect
      .persist(TransactionFailed(state.transactionId.get, s"Phase $phase failed with error: ${error.message}"))
      .thenRun { stateNew =>
        if (phase != CompensatePhase) {
          executePhase(context, state.copy(currentPhase = CompensatePhase), stepExecutorFactory, trace, replyTo)
        } else {
          replyTo.foreach(_ ! TransactionResult(successful = false, stateNew, trace))
        }
      }
  }

  private def executePhase[E, R](
                                  context: ActorContext[Command],
                                  state: State,
                                  stepExecutorFactory: (String, SagaTransactionStep[E, R]) => ActorRef[StepExecutor.Command],
                                  trace: List[StepExecutor.State[_, _]],
                                  replyTo: Option[ActorRef[TransactionResult]]
                                )(implicit ec: ExecutionContext, askTimeout: Timeout): Unit = {

    val stepsInPhase = state.steps.filter(_.phase == state.currentPhase)

    import akka.actor.typed.scaladsl.AskPattern._
    implicit val scheduler: Scheduler = context.system.scheduler

    val futureResults: Future[List[StepResult[E, R]]] = Future.sequence(
      stepsInPhase.map { step =>
        val stepExecutor = stepExecutorFactory(
          s"${state.transactionId.get}-${step.stepId}-${state.currentPhase}",
          step.asInstanceOf[SagaTransactionStep[E, R]]
        )
        stepExecutor.ask((ref: ActorRef[StepResult[E, R]]) => StepExecutor.Start[E, R](state.transactionId.get, step.asInstanceOf[SagaTransactionStep[E, R]], Some(ref)))(askTimeout, scheduler)
          .mapTo[StepResult[E, R]]
      }
    )

    futureResults.foreach(stepResults => {

      val stepStateTrace = stepResults.foldLeft(trace)((acc, result) => result match {
        case StepCompleted(tid, r, stepState) => stepState :: acc
        case StepFailed(tid, e, stepState) => stepState :: acc
      })

      val positiveResults = stepResults.foldLeft[List[Either[RetryableOrNotException, R]]](Nil)((acc, result) => result match {
        case StepCompleted(tid, r, stepState) => Right(r) :: acc
        case StepFailed(tid, e: E, stepState) => Left(NonRetryableFailure(e.toString)) :: acc
      })

      stepResults.find(_.isInstanceOf[StepFailed[_, _]]).map(firstError => {
        context.self ! PhaseFailed(state.currentPhase, NonRetryableFailure(firstError.toString), stepStateTrace, replyTo)
      }).getOrElse({
        context.self ! PhaseCompleted(state.currentPhase, positiveResults, stepStateTrace, replyTo)
      })

    })
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