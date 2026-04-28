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
  case class StartTransaction[E, R, C](transactionId: String, steps: List[SagaTransactionStep[E, R, C]], replyTo: Option[ActorRef[TransactionResult]], traceId: String = "", singleStep: Boolean = false) extends Command
  case object TransactionTimeout extends Command
  case class ProceedNext(replyTo: Option[ActorRef[TransactionResult]]) extends Command
  case class TransactionPaused(transactionId: String, traceId: String) extends Command with Event
  case class TransactionResumed(transactionId: String, traceId: String) extends Command with Event
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
  case class TransactionStarted(transactionId: String, steps: List[SagaTransactionStep[_, _, _]], traceId: String = "", singleStep: Boolean = false) extends Event
  case class PhaseSucceeded(phase: TransactionPhase) extends Event
  case class PhaseFailed(phase: TransactionPhase) extends Event
  case class TransactionCompleted(transactionId: String) extends Event
  case class TransactionFailed(transactionId: String, reason: String) extends Event
  case class TransactionSuspended(transactionId: String, reason: String) extends Event

  // State
  case class State(
                    transactionId: Option[String] = None,
                    steps: List[SagaTransactionStep[_, _, _]] = List.empty,
                    currentPhase: TransactionPhase = PreparePhase,
                    status: Status = Created,
                    traceId: String = "",
                    singleStep: Boolean = false,
                    isPaused: Boolean = false
                  )

  sealed trait Status
  case object Created extends Status
  case object InProgress extends Status
  case object Completed extends Status
  case object Failed extends Status
  case object Compensating extends Status
  case object Suspended extends Status

  // @formatter:on

  import scala.concurrent.duration._

  case object TransactionTimeoutKey

  def apply(
             persistenceId: PersistenceId,
             stepExecutorFactory: (String, SagaTransactionStep[_, _, _]) => ActorRef[StepExecutor.Command],
             globalTimeout: FiniteDuration = 5.minutes
           )(implicit ec: ExecutionContext, timeout: Timeout): Behavior[Command] = Behaviors.setup { context =>
    Behaviors.withTimers { timers =>
      EventSourcedBehavior[Command, Event, State](
        persistenceId = persistenceId,
        emptyState = State(),
        commandHandler = commandHandler(context, timers, stepExecutorFactory, globalTimeout),
        eventHandler = eventHandler
      ).receiveSignal {
        case (state, akka.persistence.typed.RecoveryCompleted) =>
          context.log.info(s"[TraceID: ${state.traceId}] RecoveryCompleted for coordinator: ${persistenceId.id}, state: $state")
          if ((state.status == InProgress || state.status == Compensating) && !state.isPaused) {
            timers.startSingleTimer(TransactionTimeoutKey, TransactionTimeout, globalTimeout)
            context.log.info(s"[TraceID: ${state.traceId}] Resuming transaction ${state.transactionId.getOrElse("")} from phase ${state.currentPhase}")
            executePhase(context, state, stepExecutorFactory.asInstanceOf[(String, SagaTransactionStep[Any, Any, Any]) => ActorRef[StepExecutor.Command]], None)
          } else if (state.isPaused) {
            context.log.info(s"[TraceID: ${state.traceId}] Transaction ${state.transactionId.getOrElse("")} recovered in PAUSED state. Waiting for ProceedNext.")
          }
      }
    }
  }

  def commandHandler(
                      context: ActorContext[Command],
                      timers: akka.actor.typed.scaladsl.TimerScheduler[Command],
                      stepExecutorFactory: (String, SagaTransactionStep[_, _, _]) => ActorRef[StepExecutor.Command],
                      globalTimeout: FiniteDuration
                    )(implicit ec: ExecutionContext, timeout: Timeout): (State, Command) => Effect[Event, State] = { (state, command) =>
    command match {
      case StartTransaction(transactionId, steps, replyTo, reqTraceId, singleStep) if state.status == Created =>
        val traceId = if (reqTraceId.isEmpty) transactionId else reqTraceId
        timers.startSingleTimer(TransactionTimeoutKey, TransactionTimeout, globalTimeout)
        
        val startEffect = Effect.persist[Event, State](TransactionStarted(transactionId, steps, traceId, singleStep))
        
        if (singleStep) {
           startEffect.thenRun { (_: State) =>
              context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(SagaProgressEvent.TransactionStarted(transactionId, steps.map(_.stepId), traceId))
              context.self ! TransactionPaused(transactionId, traceId) // Internal signal to pause
           }
        } else {
           startEffect.thenRun { (stateNew: State) =>
              context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(SagaProgressEvent.TransactionStarted(transactionId, steps.map(_.stepId), traceId))
              executePhase(context, stateNew, stepExecutorFactory, replyTo)
           }
        }

      case TransactionTimeout =>
        if (state.status == InProgress || state.status == Compensating) {
          context.log.warn(s"[TraceID: ${state.traceId}] Transaction ${state.transactionId.getOrElse("")} timed out at phase ${state.currentPhase}")
          handlePhaseFailure(context, state, state.currentPhase, NonRetryableFailure("Global transaction timeout"), stepExecutorFactory, None)
        } else {
          Effect.none
        }

      case ProceedNext(replyTo) if state.isPaused =>
        Effect
          .persist[Event, State](TransactionResumed(state.transactionId.get, state.traceId))
          .thenRun { (stateNew: State) =>
            context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(SagaProgressEvent.StepOngoing(stateNew.transactionId.get, "SYSTEM", "RESUME", stateNew.traceId)) // Special log
            executePhase(context, stateNew, stepExecutorFactory.asInstanceOf[(String, SagaTransactionStep[Any, Any, Any]) => ActorRef[StepExecutor.Command]], replyTo)
          }

      // Internal command sent to self to persist pause
      case p: TransactionPaused =>
        Effect.persist[Event, State](p).thenRun { (_: State) =>
           context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(SagaProgressEvent.TransactionSuspended(p.transactionId, "MANUAL_PAUSE", p.traceId)) // Reuse suspended for UI
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
        val persistEffect = Effect.persist[Event, State](PhaseSucceeded(PreparePhase))
        if (state.singleStep) {
           persistEffect.thenRun { (stateNew: State) =>
              context.self ! TransactionPaused(stateNew.transactionId.get, stateNew.traceId)
           }
        } else {
           persistEffect.thenRun { (stateNew: State) => executePhase(context, stateNew, stepExecutorFactory, replyTo) }
        }
      case CommitPhase =>
        Effect.persist(
          List(
            PhaseSucceeded(CommitPhase),
            TransactionCompleted(state.transactionId.get)
          )
        ).thenRun((stateNew: State) => {
          context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(SagaProgressEvent.TransactionCompleted(state.transactionId.get, stateNew.traceId))
          replyTo.foreach(_ ! TransactionResult(successful = true, stateNew))
        })
         .thenStop()
      case CompensatePhase =>
        Effect.persist(
          List(
            PhaseSucceeded(CompensatePhase),
            TransactionFailed(state.transactionId.get, "transaction failed but compensated")
          )
        ).thenRun((stateNew: State) => {
          context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(SagaProgressEvent.TransactionFailed(state.transactionId.get, "transaction failed but compensated", stateNew.traceId))
          replyTo.foreach(_ ! TransactionResult(successful = false, stateNew))
        })
         .thenStop()
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
      val persistEffect = Effect.persist[Event, State](PhaseFailed(phase))
      if (state.singleStep) {
         persistEffect.thenRun { (stateNew: State) =>
            context.self ! TransactionPaused(stateNew.transactionId.get, stateNew.traceId)
         }
      } else {
         persistEffect.thenRun { (stateNew: State) =>
            executePhase(context, stateNew, stepExecutorFactory, replyTo)
         }
      }
    } else {
      val reason = s"Phase $phase failed with error: ${error.message}"
      Effect
        .persist(PhaseFailed(phase), TransactionSuspended(state.transactionId.get, reason))
        .thenRun { (stateNew: State) =>
          context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(SagaProgressEvent.TransactionSuspended(state.transactionId.get, reason, stateNew.traceId))
          replyTo.foreach(_ ! TransactionResult(successful = false, stateNew, reason))
        }
        .thenStop()
    }
  }

  private def executePhase[E, R, C](
                                  context: ActorContext[Command],
                                  state: State,
                                  stepExecutorFactory: (String, SagaTransactionStep[E, R, C]) => ActorRef[StepExecutor.Command],
                                  replyTo: Option[ActorRef[TransactionResult]]
                                )(implicit ec: ExecutionContext, askTimeout: Timeout): Unit = {

    val stepsInPhase = state.steps.filter(_.phase == state.currentPhase).asInstanceOf[List[SagaTransactionStep[E, R, C]]]

    import akka.actor.typed.scaladsl.AskPattern._
    implicit val scheduler: Scheduler = context.system.scheduler
    val log = context.log

    val futureResults: Future[List[StepResult[E, R, C]]] = Future.sequence(
      stepsInPhase.map { step =>
        val stepExecutor = stepExecutorFactory(
          s"${state.transactionId.get}-${step.stepId}-${state.currentPhase}",
          step
        )
        stepExecutor.ask((ref: ActorRef[StepResult[E, R, C]]) => StepExecutor.Start[E, R, C](state.transactionId.get, step, Some(ref), state.traceId))(askTimeout, scheduler)
          .mapTo[StepResult[E, R, C]]
          .recoverWith {
            case _: java.util.concurrent.TimeoutException | _: akka.pattern.AskTimeoutException =>
              log.warn(s"[TraceID: ${state.traceId}] Coordinator ask timed out for step ${step.stepId}, querying status...")
              def pollStatus(retries: Int): Future[StepResult[E, R, C]] = {
                log.info(s"[TraceID: ${state.traceId}] Polling status for step ${step.stepId}, retries left: $retries")
                stepExecutor.ask((ref: ActorRef[StepExecutor.State[E, R, C]]) => StepExecutor.QueryStatus[E, R, C](ref))(askTimeout, scheduler)
                  .flatMap { executorState =>
                    executorState.status match {
                      case StepExecutor.Succeed =>
                        executorState.result match {
                          case Some(res) =>
                            Future.successful(StepExecutor.StepCompleted[E, R, C](state.transactionId.get, step.stepId, res))
                          case None =>
                            log.error(s"[TraceID: ${state.traceId}] Step ${step.stepId} succeeded but no result found")
                            Future.failed(new RuntimeException("Step succeeded but no result found"))
                        }                      case StepExecutor.Failed =>
                        Future.successful(StepExecutor.StepFailed[E, R, C](state.transactionId.get, step.stepId, executorState.lastError.getOrElse(NonRetryableFailure("Unknown error"))))
                      case _ if retries > 0 =>
                        akka.pattern.after(2.seconds, context.system.classicSystem.scheduler)(pollStatus(retries - 1))
                      case _ =>
                        Future.successful(StepExecutor.StepFailed[E, R, C](state.transactionId.get, step.stepId, RetryableFailure(s"Step still ongoing after status queries")))
                    }
                  }
                  .recover {
                    case ex: Throwable =>
                      log.warn(s"[TraceID: ${state.traceId}] Coordinator query status failed: ${ex.getMessage}")
                      StepExecutor.StepFailed[E, R, C](state.transactionId.get, step.stepId, NonRetryableFailure(s"Coordinator query status failed: ${ex.getMessage}"))
                  }
              }
              pollStatus(3)
            case ex: Throwable =>
              log.error(s"[TraceID: ${state.traceId}] Coordinator ask failed with unexpected exception: ${ex.getClass.getName} - ${ex.getMessage}")
              Future.successful(StepExecutor.StepFailed[E, R, C](state.transactionId.get, step.stepId, NonRetryableFailure(s"Coordinator ask failed: ${ex.getMessage}")))
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
      case TransactionStarted(transactionId, steps, traceId, singleStep) =>
        state.copy(transactionId = Some(transactionId), steps = steps, status = InProgress, traceId = traceId, singleStep = singleStep)
      
      case TransactionPaused(_, _) =>
        state.copy(isPaused = true)
      
      case TransactionResumed(_, _) =>
        state.copy(isPaused = false)

      case PhaseFailed(phase) =>
        phase match {
          case PreparePhase => state.copy(currentPhase = CompensatePhase, status = Compensating)
          case CommitPhase => state.copy(currentPhase = CompensatePhase, status = Compensating)
          case CompensatePhase => state
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
      case TransactionSuspended(_, _) =>
        state.copy(status = Suspended)
    }
  }
}
