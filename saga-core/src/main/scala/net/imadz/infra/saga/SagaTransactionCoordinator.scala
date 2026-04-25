package net.imadz.infra.saga

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, Scheduler}
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.util.Timeout
import net.imadz.common.CborSerializable
import net.imadz.infra.saga.SagaParticipant.{NonRetryableFailure, RetryableFailure, RetryableOrNotException, SagaResult}
import net.imadz.infra.saga.SagaPhase._
import net.imadz.infra.saga.StepExecutor.{StepCompleted, StepFailed, StepResult}
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

object SagaTransactionCoordinator {
  val tags: Vector[String] = Vector.tabulate(5)(i => s"SagaTransactionCoordinator-$i")
  val entityTypeKey: EntityTypeKey[Command] = EntityTypeKey("SagaTransactionCoordinator")


  // @formatter:off
  // Commands
  sealed trait Command extends CborSerializable
  case class StartTransaction[E, R, C](transactionId: String, steps: List[SagaTransactionStep[E, R, C]], replyTo: Option[ActorRef[TransactionResult]]) extends Command
  private case class PhaseCompleted(phase: TransactionPhase, results: List[Either[RetryableOrNotException, Any]], replyTo: Option[ActorRef[TransactionResult]]) extends Command
  private case class PhaseFailure(phase: TransactionPhase, error: RetryableOrNotException, replyTo: Option[ActorRef[TransactionResult]]) extends Command
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
  case class ErrorInfo(
                        message: String,
                        errorType: String,
                        isRetryable: Boolean
                      )
  case class TransactionResult(successful: Boolean, state: State, failReason: String = "")
  // Events
  sealed trait Event extends CborSerializable
  case class TransactionStarted(transactionId: String, steps: List[SagaTransactionStep[_, _, _]]) extends Event
  case class PhaseSucceeded(phase: TransactionPhase) extends Event
  case class PhaseFailed(phase: TransactionPhase) extends Event
  case class TransactionCompleted(transactionId: String) extends Event
  case class TransactionFailed(transactionId: String, reason: String) extends Event

  // State
  case class State(
                    transactionId: Option[String] = None,
                    steps: List[SagaTransactionStep[_, _, _]] = List.empty,
                    currentPhase: TransactionPhase = PreparePhase,
                    status: Status = Created
                  )

  sealed trait Status
  case object Created extends Status
  case object InProgress extends Status
  case object Completed extends Status
  case object Failed extends Status
  case object Compensating extends Status

  // @formatter:on

  def apply(
             persistenceId: PersistenceId,
             stepExecutorFactory: (String, SagaTransactionStep[_, _, _]) => ActorRef[StepExecutor.Command]
           )(implicit ec: ExecutionContext, timeout: Timeout): Behavior[Command] = Behaviors.setup { context =>
    EventSourcedBehavior[Command, Event, State](
      persistenceId = persistenceId,
      emptyState = State(),
      commandHandler = commandHandler(context, stepExecutorFactory),
      eventHandler = eventHandler
    )
  }

  def commandHandler(
                      context: ActorContext[Command],
                      stepExecutorFactory: (String, SagaTransactionStep[_, _, _]) => ActorRef[StepExecutor.Command]
                    )(implicit ec: ExecutionContext, timeout: Timeout): (State, Command) => Effect[Event, State] = { (state, command) =>
    command match {
      case StartTransaction(transactionId, steps, replyTo) if state.status == Created =>
        Effect
          .persist(TransactionStarted(transactionId, steps))
          .thenRun { _ =>
            executePhase(context, State(Some(transactionId), steps, PreparePhase, InProgress), stepExecutorFactory, replyTo)
          }

      case PhaseCompleted(phase, results, replyTo) =>
        handlePhaseCompletion(context, state, phase, results, stepExecutorFactory, replyTo)

      case PhaseFailure(phase, error, replyTo) =>
        handlePhaseFailure(context, state, phase, error, stepExecutorFactory, replyTo)

      case _ => Effect.none
    }
  }

  private def handlePhaseCompletion(
                                     context: ActorContext[Command],
                                     state: State,
                                     phase: TransactionPhase,
                                     results: List[Either[RetryableOrNotException, Any]],
                                     stepExecutorFactory: (String, SagaTransactionStep[_, _, _]) => ActorRef[StepExecutor.Command],
                                     replyTo: Option[ActorRef[TransactionResult]]
                                   )(implicit ec: ExecutionContext, timeout: Timeout): Effect[Event, State] = {
    phase match {
      case PreparePhase =>
        Effect
          .persist(PhaseSucceeded(PreparePhase))
          .thenRun { stateNew => executePhase(context, stateNew, stepExecutorFactory, replyTo) }
      case CommitPhase =>
        Effect.persist(
          List(
            PhaseSucceeded(CommitPhase),
            TransactionCompleted(state.transactionId.get)
          )
        ).thenRun(stateNew => replyTo.foreach(_ ! TransactionResult(successful = true, stateNew)))
      case CompensatePhase =>
        Effect.persist(
          List(
            PhaseSucceeded(CompensatePhase),
            TransactionFailed(state.transactionId.get, "transaction failed but compensated")
          )
        ).thenRun(stateNew => replyTo.foreach(_ ! TransactionResult(successful = false, stateNew)))
    }
  }

  private def handlePhaseFailure(
                                  context: ActorContext[Command],
                                  state: State,
                                  phase: TransactionPhase,
                                  error: RetryableOrNotException,
                                  stepExecutorFactory: (String, SagaTransactionStep[_, _, _]) => ActorRef[StepExecutor.Command],
                                  replyTo: Option[ActorRef[TransactionResult]]
                                )(implicit ec: ExecutionContext, timeout: Timeout): Effect[Event, State] = {
    if (phase != CompensatePhase) {
      Effect
        .persist(PhaseFailed(phase))
        .thenRun { stateNew =>
          executePhase(context, stateNew, stepExecutorFactory, replyTo)
        }
    } else {
      Effect
        .persist(PhaseFailed(phase), TransactionFailed(state.transactionId.get, s"Phase $phase failed with error: ${error.message}"))
        .thenRun { stateNew =>
          replyTo.foreach(_ ! TransactionResult(successful = false, stateNew, s"Phase $phase failed with error: ${error.message}"))
        }
    }
  }

  private def executePhase[E, R, C](
                                  context: ActorContext[Command],
                                  state: State,
                                  stepExecutorFactory: (String, SagaTransactionStep[E, R, C]) => ActorRef[StepExecutor.Command],
                                  replyTo: Option[ActorRef[TransactionResult]]
                                )(implicit ec: ExecutionContext, askTimeout: Timeout): Unit = {

    val stepsInPhase = state.steps.filter(_.phase == state.currentPhase)

    import akka.actor.typed.scaladsl.AskPattern._
    implicit val scheduler: Scheduler = context.system.scheduler

    val futureResults: Future[List[StepResult[E, R, C]]] = Future.sequence(
      stepsInPhase.map { step =>
        val stepExecutor = stepExecutorFactory(
          s"${state.transactionId.get}-${step.stepId}-${state.currentPhase}",
          step.asInstanceOf[SagaTransactionStep[E, R, C]]
        )
        stepExecutor.ask((ref: ActorRef[StepResult[E, R, C]]) => StepExecutor.Start[E, R, C](state.transactionId.get, step.asInstanceOf[SagaTransactionStep[E, R, C]], Some(ref)))(askTimeout, scheduler)
          .mapTo[StepResult[E, R, C]]
          .recover {
            case ex: Throwable =>
              StepExecutor.StepFailed[E, R, C](state.transactionId.get, step.stepId, NonRetryableFailure(s"Coordinator ask failed: ${ex.getMessage}"))
          }
      }
    )

    futureResults.foreach(stepResults => {

      val positiveResults = stepResults.foldLeft[List[Either[RetryableOrNotException, SagaResult[R]]]](Nil)((acc, result) => result match {
        case StepCompleted(tid, sid, r) => Right(r) :: acc
        case StepFailed(tid, sid, e) => Left(NonRetryableFailure(e.toString)) :: acc
      })

      stepResults.find(_.isInstanceOf[StepFailed[_, _, _]]).map(firstError => {
        context.self ! PhaseFailure(state.currentPhase, NonRetryableFailure(firstError.toString), replyTo)
      }).getOrElse({
        context.self ! PhaseCompleted(state.currentPhase, positiveResults, replyTo)
      })

    })
  }

  def eventHandler: (State, Event) => State = { (state, event) =>
    event match {
      case TransactionStarted(transactionId, steps) =>
        state.copy(transactionId = Some(transactionId), steps = steps, status = InProgress)
      case PhaseFailed(phase) =>
        phase match {
          case PreparePhase => state.copy(currentPhase = CompensatePhase, status = Compensating)
          case CommitPhase => state.copy(currentPhase = CompensatePhase, status = Compensating)
          case CompensatePhase => state.copy(status = Failed)
        }
      case PhaseSucceeded(phase) =>
        phase match {
          case PreparePhase => state.copy(currentPhase = CommitPhase)
          case CommitPhase => state.copy(status = Completed)
          case CompensatePhase => state
        }
      case TransactionCompleted(_) =>
        state.copy(status = Completed)
      case TransactionFailed(_, _) =>
        state.copy(status = Failed)
    }
  }
}