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
  case class ResolveSuspended(replyTo: Option[ActorRef[TransactionResult]]) extends Command
  case class ManualFixStep(stepId: String, phase: TransactionPhase, replyTo: Option[ActorRef[TransactionResult]]) extends Command
  case class RetryCurrentPhase(replyTo: Option[ActorRef[TransactionResult]]) extends Command
  case class ProceedNextGroup(replyTo: Option[ActorRef[TransactionResult]]) extends Command
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
  case class StepGroupSucceeded(phase: TransactionPhase, group: Int) extends Event
  case class PhaseFailed(phase: TransactionPhase) extends Event
  case class TransactionCompleted(transactionId: String) extends Event
  case class TransactionFailed(transactionId: String, reason: String) extends Event
  case class TransactionSuspended(transactionId: String, reason: String) extends Event
  case class TransactionResolved(transactionId: String) extends Event
  case class TransactionRetried(transactionId: String, phase: TransactionPhase) extends Event

  // State
  case class State(
                    transactionId: Option[String] = None,
                    steps: List[SagaTransactionStep[_, _, _]] = List.empty,
                    currentPhase: TransactionPhase = PreparePhase,
                    status: Status = Created,
                    traceId: String = "",
                    singleStep: Boolean = false,
                    isPaused: Boolean = false,
                    currentStepGroup: Int = 1
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

      case ResolveSuspended(replyTo) if state.status == Suspended =>
        Effect
          .persist[Event, State](TransactionResolved(state.transactionId.get))
          .thenRun { (stateNew: State) =>
            context.log.info(s"[TraceID: ${state.traceId}] Resolving suspended transaction ${state.transactionId.getOrElse("")}, re-executing phase ${state.currentPhase} at group ${stateNew.currentStepGroup}")
            executePhase(context, stateNew, stepExecutorFactory.asInstanceOf[(String, SagaTransactionStep[Any, Any, Any]) => ActorRef[StepExecutor.Command]], replyTo)
          }

      case ProceedNextGroup(replyTo) =>
        if ((state.status == InProgress || state.status == Compensating) && !state.isPaused) {
          context.log.info(s"[TraceID: ${state.traceId}] Proceeding to next group ${state.currentStepGroup} in phase ${state.currentPhase}")
          executePhase(context, state, stepExecutorFactory.asInstanceOf[(String, SagaTransactionStep[Any, Any, Any]) => ActorRef[StepExecutor.Command]], replyTo)
        } else {
          context.log.info(s"Ignoring ProceedNextGroup because state is ${state.status} and paused=${state.isPaused}")
        }
        Effect.none

      case RetryCurrentPhase(replyTo) =>
        val phaseToRetry = if (state.status == Compensating && state.currentPhase == CompensatePhase) PreparePhase else state.currentPhase
        Effect.persist(TransactionRetried(state.transactionId.get, phaseToRetry)).thenRun { (stateNew: State) =>
           context.log.info(s"[TraceID: ${state.traceId}] Retrying phase ${stateNew.currentPhase} at group ${stateNew.currentStepGroup}")
           executePhase(context, stateNew, stepExecutorFactory.asInstanceOf[(String, SagaTransactionStep[Any, Any, Any]) => ActorRef[StepExecutor.Command]], replyTo)
        }

      case ManualFixStep(stepId, phase, replyTo) =>
        state.transactionId match {
          case Some(tid) =>
            val name = s"$tid-$stepId-$phase"
            val step = state.steps.find(s => s.stepId == stepId && s.phase == phase)
            step match {
              case Some(s) =>
                val executor = context.child(name) match {
                  case Some(ref) => ref.asInstanceOf[ActorRef[StepExecutor.Command]]
                  case None => stepExecutorFactory(name, s)
                }
                // 我们不直接等待 StepExecutor 的回复，因为 ResolveSuspended 会重新触发 executePhase 并收集结果
                executor ! StepExecutor.ManualFix(None)
                // 立即给管理员一个回复表示指令已送达（由于是异步，这里简单回复成功，或者可以改进协议）
                // 暂时不回复或者添加特定的内部确认
              case None =>
                context.log.warn(s"Step $stepId not found in phase $phase for transaction $tid")
            }
          case None => context.log.warn("ManualFixStep received but transactionId is missing")
        }
        Effect.none

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

    val groupsInPhase = state.steps.filter(_.phase == phase).map(_.stepGroup).distinct.sorted

    phase match {
      case PreparePhase =>
        val nextGroupOpt = groupsInPhase.find(_ > state.currentStepGroup)
        nextGroupOpt match {
          case Some(nextGroup) =>
             Effect.persist(StepGroupSucceeded(PreparePhase, state.currentStepGroup)).thenRun { (stateNew: State) =>
                if (state.singleStep) {
                   context.self ! TransactionPaused(stateNew.transactionId.get, stateNew.traceId)
                } else {
                   context.self ! ProceedNextGroup(replyTo)
                }
             }
          case None => // All groups in Prepare finished
            val persistEffect = Effect.persist[Event, State](PhaseSucceeded(PreparePhase))
            if (state.singleStep) {
               persistEffect.thenRun { (stateNew: State) =>
                  context.self ! TransactionPaused(stateNew.transactionId.get, stateNew.traceId)
               }
            } else {
               persistEffect.thenRun { (stateNew: State) => executePhase(context, stateNew, stepExecutorFactory, replyTo) }
            }
        }

      case CommitPhase =>
        val nextGroupOpt = groupsInPhase.find(_ > state.currentStepGroup)
        nextGroupOpt match {
           case Some(nextGroup) =>
              Effect.persist(StepGroupSucceeded(CommitPhase, state.currentStepGroup)).thenRun { (_: State) =>
                 context.self ! ProceedNextGroup(replyTo)
              }
           case None =>
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
        }

      case CompensatePhase =>
        val nextGroupOpt = groupsInPhase.reverse.find(_ < state.currentStepGroup)
        nextGroupOpt match {
           case Some(nextGroup) =>
              Effect.persist(StepGroupSucceeded(CompensatePhase, state.currentStepGroup)).thenRun { (_: State) =>
                 context.self ! ProceedNextGroup(replyTo)
              }
           case None =>
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

    val stepsInPhase = state.steps
      .filter(s => s.phase == state.currentPhase && s.stepGroup == state.currentStepGroup)
      .asInstanceOf[List[SagaTransactionStep[E, R, C]]]

    if (stepsInPhase.isEmpty) {
       context.log.info(s"[TraceID: ${state.traceId}] No steps found for phase ${state.currentPhase} and group ${state.currentStepGroup}, completing group/phase...")
       context.self ! PhaseCompleted(state.currentPhase, Nil, replyTo)
       return
    }

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
                        val promise = scala.concurrent.Promise[StepResult[E, R, C]]()
                        context.system.scheduler.scheduleOnce(2.seconds, new Runnable {
                          override def run(): Unit = promise.completeWith(pollStatus(retries - 1))
                        })
                        promise.future
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
        val firstGroupInPrepare = steps.filter(_.phase == PreparePhase).map(_.stepGroup).distinct.sorted.headOption.getOrElse(1)
        state.copy(transactionId = Some(transactionId), steps = steps, status = InProgress, traceId = traceId, singleStep = singleStep, currentStepGroup = firstGroupInPrepare)
      
      case TransactionPaused(_, _) =>
        state.copy(isPaused = true)
      
      case TransactionResumed(_, _) =>
        state.copy(isPaused = false)

      case PhaseFailed(phase) =>
        val maxGroupInCompensate = state.steps.filter(_.phase == CompensatePhase).map(_.stepGroup).distinct.sorted.lastOption.getOrElse(1)
        phase match {
          case PreparePhase => state.copy(currentPhase = CompensatePhase, status = Compensating, currentStepGroup = maxGroupInCompensate)
          case CommitPhase => state.copy(currentPhase = CompensatePhase, status = Compensating, currentStepGroup = maxGroupInCompensate)
          case CompensatePhase => state
        }
      case StepGroupSucceeded(phase, group) =>
        val groupsInPhase = state.steps.filter(_.phase == phase).map(_.stepGroup).distinct.sorted
        if (phase == CompensatePhase) {
          val nextGroup = groupsInPhase.reverse.find(_ < group).getOrElse(groupsInPhase.headOption.getOrElse(1))
          state.copy(currentStepGroup = nextGroup)
        } else {
          val nextGroup = groupsInPhase.find(_ > group).getOrElse(groupsInPhase.lastOption.getOrElse(1))
          state.copy(currentStepGroup = nextGroup)
        }
      case PhaseSucceeded(phase) =>
        phase match {
          case PreparePhase => 
            val firstGroupInCommit = state.steps.filter(_.phase == CommitPhase).map(_.stepGroup).distinct.sorted.headOption.getOrElse(1)
            state.copy(currentPhase = CommitPhase, currentStepGroup = firstGroupInCommit)
          case CommitPhase => state.copy(status = Completed)
          case CompensatePhase => state
        }
      case TransactionCompleted(_) =>
        state.copy(status = Completed)
      case TransactionFailed(_, _) =>
        state.copy(status = Failed)
      case TransactionSuspended(_, _) =>
        state.copy(status = Suspended)
      case TransactionResolved(_) =>
        val newStatus = if (state.currentPhase == CompensatePhase) Compensating else InProgress
        state.copy(status = newStatus)
      case TransactionRetried(_, phase) =>
        val firstGroupInPhase = state.steps.filter(_.phase == phase).map(_.stepGroup).distinct.sorted.headOption.getOrElse(1)
        state.copy(currentPhase = phase, status = InProgress, currentStepGroup = firstGroupInPhase)
    }
  }
}
