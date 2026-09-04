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
import net.imadz.infra.saga.dsl.{SagaDefinition, SagaRegistry}
import net.imadz.infra.saga.persistence.SagaTransactionCoordinatorEventAdapter
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

object SagaTransactionCoordinator {
  val tags: Vector[String] = Vector.tabulate(5)(i => s"SagaTransactionCoordinator-$i")
  val entityTypeKey: EntityTypeKey[Command] = EntityTypeKey("SagaTransactionCoordinator")

  // @formatter:off
  // Commands
  sealed trait Command extends CborSerializable

  /** Idempotent start. The journal records (definition name+version, args, step descriptors);
    * participants are deterministically rebuilt from the registered SagaDefinition. */
  case class StartSaga(transactionId: String,
                       definitionName: String,
                       definitionVersion: Int,
                       argsBytes: Array[Byte],
                       traceId: String = "",
                       singleStep: Boolean = false,
                       replyTo: Option[ActorRef[SagaStartReply]] = None,
                       completionReply: Option[ActorRef[TransactionResult]] = None) extends Command

  sealed trait SagaStartReply extends CborSerializable
  case object Started extends SagaStartReply
  case class AlreadyRunning(snapshot: StatusSnapshot) extends SagaStartReply
  case class AlreadyFinished(successful: Boolean, failReason: Option[String], steps: List[StepSpecSnapshot]) extends SagaStartReply

  sealed trait StartRejection extends SagaStartReply
  case object UnknownDefinition extends StartRejection
  case object ConflictingArgs extends StartRejection
  case object MaterializeFailed extends StartRejection
  case class PreCheckFailed(code: String, message: String) extends StartRejection

  /** Rich status query �?live step states for the current position, coarse for the rest. */
  case class GetTransactionStatus(transactionId: String, replyTo: ActorRef[Option[StatusSnapshot]]) extends Command

  case object TransactionTimeout extends Command
  case class ProceedNext(completionReply: Option[ActorRef[TransactionResult]]) extends Command
  case class ResolveSuspended(completionReply: Option[ActorRef[TransactionResult]]) extends Command
  case class ManualFixStep(stepId: String, phase: TransactionPhase, completionReply: Option[ActorRef[TransactionResult]] = None) extends Command
  case class RetryCurrentPhase(completionReply: Option[ActorRef[TransactionResult]]) extends Command
  case class ProceedNextGroup(completionReply: Option[ActorRef[TransactionResult]]) extends Command
  case class TransactionPaused(transactionId: String, traceId: String) extends Command with Event
  case class TransactionResumed(transactionId: String, traceId: String) extends Command with Event
  private case class PhaseCompleted(phase: TransactionPhase, results: List[Either[RetryableOrNotException, Any]], outcomes: List[StepOutcome], completionReply: Option[ActorRef[TransactionResult]]) extends Command
  private case class PhaseFailure(phase: TransactionPhase, error: RetryableOrNotException, outcomes: List[StepOutcome], completionReply: Option[ActorRef[TransactionResult]]) extends Command
  private case class StatusCollected(states: List[StepExecutor.State[Any, Any, Any]], replyTo: ActorRef[Option[StatusSnapshot]]) extends Command
  private case object RecoveredInProgress extends Command

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
      val descriptor = state.stepDescriptor.getOrElse(throw new IllegalStateException(s"Step $stepNumber has no associated step descriptor"))
      TracingStep(
        stepNumber = stepNumber,
        stepId = descriptor.stepId,
        stepType = "SagaTransactionStep",
        phase = descriptor.phase.toString,
        participant = descriptor.participantName,
        status = state.status.toString,
        retries = state.retries,
        maxRetries = descriptor.maxRetries,
        timeoutInMillis = descriptor.timeoutDuration.toMillis,
        retryWhenRecoveredOngoing = descriptor.retryWhenRecoveredOngoing,
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

  /** Wire-safe terminal result �?carries a snapshot, never the engine State. */
  case class TransactionResult(successful: Boolean, snapshot: StatusSnapshot, failReason: String = "") extends CborSerializable

  case class StatusSnapshot(
                             transactionId: String,
                             definitionName: String,
                             definitionVersion: Int,
                             traceId: String,
                             status: String,
                             currentPhase: String,
                             currentStepGroup: Int,
                             isPaused: Boolean,
                             singleStep: Boolean,
                             failReason: Option[String],
                             steps: List[StepSpecSnapshot]
                           ) extends CborSerializable

  case class StepSpecSnapshot(
                               stepId: String,
                               phase: String,
                               participantName: String,
                               stepGroup: Int,
                               maxRetries: Int,
                               timeoutInMillis: Long,
                               retryWhenRecoveredOngoing: Boolean,
                               status: String,
                               retries: Int = 0,
                               error: Option[ErrorInfo] = None
                             ) extends CborSerializable

  // Events �?steps persist as static descriptors, participants never enter the journal
  sealed trait Event extends CborSerializable
  case class TransactionStarted(transactionId: String,
                                definitionName: String,
                                definitionVersion: Int,
                                argsBytes: Array[Byte],
                                argsHash: String,
                                steps: List[StepDescriptor],
                                traceId: String = "",
                                singleStep: Boolean = false) extends Event
  case class PhaseSucceeded(phase: TransactionPhase, outcomes: List[StepOutcome] = Nil) extends Event
  case class StepGroupSucceeded(phase: TransactionPhase, group: Int, outcomes: List[StepOutcome] = Nil) extends Event
  case class PhaseFailed(phase: TransactionPhase, outcomes: List[StepOutcome] = Nil) extends Event
  case class TransactionCompleted(transactionId: String) extends Event
  case class TransactionFailed(transactionId: String, reason: String) extends Event
  case class TransactionSuspended(transactionId: String, reason: String) extends Event
  case class TransactionResolved(transactionId: String) extends Event
  case class TransactionRetried(transactionId: String, phase: TransactionPhase) extends Event

  /** Journaled per-step result recorded with phase-completion events so status snapshots
    * stay exact after the step executors have stopped (they stop on terminal replies). */
  case class StepOutcome(stepId: String, phase: TransactionPhase, status: String) extends CborSerializable

  /** Operator-intent event: the named step is treated as succeeded (manual external fix).
    * Journaled here, never delegated to step executors, so recovery is deterministic. */
  case class StepManuallyFixed(stepId: String, phase: TransactionPhase) extends Event

  // State �?descriptors only; participant instances live in the node-local materialization cache
  case class State(
                    transactionId: Option[String] = None,
                    definitionName: Option[String] = None,
                    definitionVersion: Option[Int] = None,
                    argsBytes: Option[Array[Byte]] = None,
                    argsHash: Option[String] = None,
                    steps: List[StepDescriptor] = List.empty,
                    currentPhase: TransactionPhase = PreparePhase,
                    status: Status = Created,
                    traceId: String = "",
                    singleStep: Boolean = false,
                    isPaused: Boolean = false,
                    currentStepGroup: Int = 1,
                    failReason: Option[String] = None,
                    manuallyFixed: Set[(String, TransactionPhase)] = Set.empty,
                    stepOutcomes: Map[(String, TransactionPhase), StepOutcome] = Map.empty
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

  class SagaDefinitionDriftException(message: String) extends RuntimeException(message)

  /** Node-local cache of materialized (participant-carrying) steps �?populated at StartSaga
    * (pre-materialization) and lazily re-materialized after passivation/restart. */
  final class MaterializedSteps {
    private var cache: Map[String, SagaTransactionStep[Any, Any, Any]] = Map.empty

    def put(steps: List[SagaTransactionStep[Any, Any, Any]]): Unit =
      cache = steps.map(s => key(s.stepId, s.phase) -> s).toMap

    def clear(): Unit = cache = Map.empty

    def size: Int = cache.size

    def get(stepId: String, phase: TransactionPhase): Option[SagaTransactionStep[Any, Any, Any]] =
      cache.get(key(stepId, phase))

    def stepsFor(phase: TransactionPhase, group: Int): List[SagaTransactionStep[Any, Any, Any]] =
      cache.values.filter(s => s.phase == phase && s.stepGroup == group).toList

    private def key(stepId: String, phase: TransactionPhase): String = s"$stepId-${phase.key}"
  }

  def apply(
              persistenceId: PersistenceId,
              stepExecutorBehavior: String => Behavior[StepExecutor.Command],
              globalTimeout: FiniteDuration = 5.minutes
            )(implicit ec: ExecutionContext, timeout: Timeout): Behavior[Command] = Behaviors.setup { context =>
    Behaviors.withTimers { timers =>
      val cache = new MaterializedSteps
      var phaseInFlight = false // transient double-fire guard for group re-drives; reset on result receipt/recovery

      val selectedTag = tags(math.abs(persistenceId.id.hashCode % tags.size))

      EventSourcedBehavior[Command, Event, State](
        persistenceId = persistenceId,
        emptyState = State(),
        commandHandler = commandHandler(context, timers, stepExecutorBehavior, globalTimeout, cache, () => phaseInFlight, b => phaseInFlight = b),
        eventHandler = eventHandler
      )
        .withTagger(_ => Set(selectedTag))
        // saga_v3 journal: domain events are persisted as proto POs (same pattern as StepExecutor).
        .eventAdapter(new SagaTransactionCoordinatorEventAdapter(context.system.classicSystem.asInstanceOf[akka.actor.ExtendedActorSystem]))
        .receiveSignal {
        case (state, akka.persistence.typed.RecoveryCompleted) =>
          phaseInFlight = false
          if ((state.status == InProgress || state.status == Compensating) && !state.isPaused) {
            timers.startSingleTimer(TransactionTimeoutKey, TransactionTimeout, globalTimeout)
            context.log.info(s"[TraceID: ${state.traceId}] RecoveryCompleted: resuming transaction ${state.transactionId.getOrElse("")} from phase ${state.currentPhase}")
            // Cannot persist inside receiveSignal �?delegate materialization/suspension to a self-command.
            context.self ! RecoveredInProgress
          } else if (state.isPaused) {
            context.log.info(s"[TraceID: ${state.traceId}] Transaction ${state.transactionId.getOrElse("")} recovered in PAUSED state. Waiting for ProceedNext.")
          }
      }
    }
  }

  // ============================================================
  // Materialization: participants are rebuilt from (definition, args).
  // Pure computation �?always runs inside command bodies, never in thenRun/receiveSignal.
  // ============================================================

  private def materialize(state: State): Try[List[SagaTransactionStep[Any, Any, Any]]] =
    for {
      definitionName <- state.definitionName.fold[Try[String]](Failure(new IllegalStateException("no definition name in state")))(Success(_))
      definitionVersion <- state.definitionVersion.fold[Try[Int]](Failure(new IllegalStateException("no definition version in state")))(Success(_))
      argsBytes <- state.argsBytes.fold[Try[Array[Byte]]](Failure(new IllegalStateException("no args in state")))(Success(_))
      definition <- SagaRegistry.resolve(definitionName, definitionVersion)
      defAny = definition.asInstanceOf[SagaDefinition[Any, Any, Any]]
      args <- defAny.argsCodec.decode(argsBytes)
      steps <- defAny.expand(args)
      _ <- validateStructure(state.steps, steps)
    } yield steps

  /** Structural (stepId + phase + group) validation �?resilience values are tunable and
    * may drift between versions without suspending in-flight transactions. */
  private def validateStructure(persisted: List[StepDescriptor], materialized: List[SagaTransactionStep[_, _, _]]): Try[Unit] = {
    val persistedKeys = persisted.map(d => (d.stepId, d.phase.toString, d.stepGroup)).sorted
    val materializedKeys = materialized.map(s => (s.stepId, s.phase.toString, s.stepGroup)).sorted
    if (persistedKeys == materializedKeys) Success(())
    else Failure(new SagaDefinitionDriftException(
      s"Persisted step plan $persistedKeys does not match definition plan $materializedKeys"))
  }

  private def ensureMaterialized(cache: MaterializedSteps, state: State): Try[Unit] =
    if (state.steps.nonEmpty && cache.size == state.steps.size) Success(())
    else materialize(state).map(steps => cache.put(steps))

  def commandHandler(
                      context: ActorContext[Command],
                      timers: akka.actor.typed.scaladsl.TimerScheduler[Command],
                      stepExecutorBehavior: String => Behavior[StepExecutor.Command],
                      globalTimeout: FiniteDuration,
                      cache: MaterializedSteps,
                      isInFlight: () => Boolean,
                      setInFlight: Boolean => Unit
                    )(implicit ec: ExecutionContext, timeout: Timeout): (State, Command) => Effect[Event, State] = { (state, command) =>
    command match {
      case StartSaga(transactionId, definitionName, definitionVersion, argsBytes, reqTraceId, singleStep, startReply, completionReply) =>
        handleStartSaga(context, state, timers, transactionId, definitionName, definitionVersion, argsBytes, reqTraceId, singleStep, startReply, completionReply, globalTimeout, stepExecutorBehavior, cache)

      case GetTransactionStatus(txId, replyTo) =>
        handleGetTransactionStatus(context, state, cache, txId, replyTo)

      case RecoveredInProgress =>
        materialize(state) match {
          case Failure(ex) =>
            context.log.error(s"[TraceID: ${state.traceId}] Materialization failed after recovery: ${ex.getMessage}")
            suspendEffect(context, state, s"materialize failed after recovery: ${ex.getMessage}", completionReply = None)
          case Success(steps) =>
            cache.put(steps)
            Effect.none.thenRun(_ => executePhase(context, state, stepExecutorBehavior, cache, completionReply = None))
        }

      case TransactionTimeout =>
        // A paused transaction (singleStep debug session / operator hold) is driven by the
        // operator, not by the global timeout; the timer restarts on resume.
        if ((state.status == InProgress || state.status == Compensating) && !state.isPaused) {
          context.log.warn(s"[TraceID: ${state.traceId}] Transaction ${state.transactionId.getOrElse("")} timed out at phase ${state.currentPhase}")
          handlePhaseFailure(context, state, state.currentPhase, NonRetryableFailure("Global transaction timeout"), Nil, stepExecutorBehavior, cache, completionReply = None)
        } else {
          Effect.none
        }

      case ProceedNext(completionReply) if state.isPaused =>
        ensureMaterialized(cache, state) match {
          case Failure(ex) => suspendEffect(context, state, s"materialize failed: ${ex.getMessage}", completionReply)
          case Success(_) =>
            timers.startSingleTimer(TransactionTimeoutKey, TransactionTimeout, globalTimeout)
            Effect
              .persist[Event, State](TransactionResumed(state.transactionId.get, state.traceId))
              .thenRun { (stateNew: State) =>
                context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(SagaProgressEvent.StepOngoing(stateNew.transactionId.get, "SYSTEM", "RESUME", stateNew.traceId))
                executePhase(context, stateNew, stepExecutorBehavior, cache, completionReply)
              }
        }

      case ResolveSuspended(completionReply) if state.status == Suspended =>
        materialize(state) match {
          case Failure(ex) =>
            // Already suspended; stay suspended, log and let ops retry after fixing the registry.
            context.log.warn(s"[TraceID: ${state.traceId}] ResolveSuspended could not materialize: ${ex.getMessage}")
            Effect.none
          case Success(steps) =>
            cache.put(steps)
            timers.startSingleTimer(TransactionTimeoutKey, TransactionTimeout, globalTimeout)
            Effect
              .persist[Event, State](TransactionResolved(state.transactionId.get))
              .thenRun { (stateNew: State) =>
                context.log.info(s"[TraceID: ${state.traceId}] Resolving suspended transaction ${state.transactionId.getOrElse("")}, re-executing phase ${stateNew.currentPhase} at group ${stateNew.currentStepGroup}")
                executePhase(context, stateNew, stepExecutorBehavior, cache, completionReply)
              }
        }

      case ProceedNextGroup(completionReply) =>
        if (isInFlight()) {
          context.log.info(s"[TraceID: ${state.traceId}] Ignoring ProceedNextGroup �?a phase dispatch is already in flight")
          Effect.none
        } else if ((state.status == InProgress || state.status == Compensating) && !state.isPaused) {
          ensureMaterialized(cache, state) match {
            case Failure(ex) => suspendEffect(context, state, s"materialize failed: ${ex.getMessage}", completionReply)
            case Success(_) =>
              context.log.info(s"[TraceID: ${state.traceId}] Proceeding to next group ${state.currentStepGroup} in phase ${state.currentPhase}")
              Effect.none.thenRun { (_: State) =>
                executePhase(context, state, stepExecutorBehavior, cache, completionReply)
              }
          }
        } else {
          context.log.info(s"Ignoring ProceedNextGroup because state is ${state.status} and paused=${state.isPaused}")
          Effect.none
        }

      case RetryCurrentPhase(completionReply) =>
        ensureMaterialized(cache, state) match {
          case Failure(ex) => suspendEffect(context, state, s"materialize failed: ${ex.getMessage}", completionReply)
          case Success(_) =>
            val phaseToRetry = if (state.status == Compensating && state.currentPhase == CompensatePhase) PreparePhase else state.currentPhase
            timers.startSingleTimer(TransactionTimeoutKey, TransactionTimeout, globalTimeout)
            Effect.persist(TransactionRetried(state.transactionId.get, phaseToRetry)).thenRun { (stateNew: State) =>
              context.log.info(s"[TraceID: ${state.traceId}] Retrying phase ${stateNew.currentPhase} at group ${stateNew.currentStepGroup}")
              executePhase(context, stateNew, stepExecutorBehavior, cache, completionReply)
            }
        }

      case ManualFixStep(stepId, phase, completionReply) =>
        if (state.status == Suspended && state.transactionId.isDefined) {
          // Journal-first: the fix intent lives in the coordinator's own event stream, so
          // recovery is deterministic and never races executor message delivery. The
          // following resolveSuspended re-drives the phase; this step is skipped there.
          Effect.persist(StepManuallyFixed(stepId, phase)).thenRun { (stateNew: State) =>
            notifyExecutorManualFix(context, stateNew, stepExecutorBehavior, cache, stepId, phase)
          }
        } else {
          // Legacy non-suspended path (best-effort executor notify), preserved unchanged.
          notifyExecutorManualFix(context, state, stepExecutorBehavior, cache, stepId, phase)
          Effect.none
        }

      // Internal command sent to self to persist pause
      case p: TransactionPaused =>
        Effect.persist[Event, State](p).thenRun { (_: State) =>
           context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(SagaProgressEvent.TransactionSuspended(p.transactionId, "MANUAL_PAUSE", p.traceId)) // Reuse suspended for UI
        }

      case PhaseCompleted(phase, results, outcomes, completionReply) =>
        setInFlight(false)
        handlePhaseCompletion(context, state, phase, results, outcomes, stepExecutorBehavior, cache, completionReply)

      case PhaseFailure(phase, error, outcomes, completionReply) =>
        setInFlight(false)
        handlePhaseFailure(context, state, phase, error, outcomes, stepExecutorBehavior, cache, completionReply)

      case StatusCollected(states, replyTo) =>
        replyTo ! Some(richSnapshot(state, states))
        Effect.none

      case _ => Effect.none
    }
  }

  // ============================================================
  // StartSaga: idempotency matrix + pre-materialization
  // ============================================================

  private def handleStartSaga(
                               context: ActorContext[Command],
                               state: State,
                               timers: akka.actor.typed.scaladsl.TimerScheduler[Command],
                               transactionId: String,
                               definitionName: String,
                               definitionVersion: Int,
                               argsBytes: Array[Byte],
                               reqTraceId: String,
                               singleStep: Boolean,
                               startReply: Option[ActorRef[SagaStartReply]],
                               completionReply: Option[ActorRef[TransactionResult]],
                               globalTimeout: FiniteDuration,
                               stepExecutorBehavior: String => Behavior[StepExecutor.Command],
                               cache: MaterializedSteps
                             )(implicit ec: ExecutionContext, timeout: Timeout): Effect[Event, State] = {
    val argsHash = net.imadz.infra.saga.dsl.ArgsHash.sha256(argsBytes)
    state.status match {
      case Created =>
        SagaRegistry.resolve(definitionName, definitionVersion) match {
          case Failure(ex) =>
            context.log.warn(s"StartSaga rejected for $transactionId: ${ex.getMessage}")
            startReply.foreach(_ ! UnknownDefinition)
            Effect.none
          case Success(definition) =>
            val defAny = definition.asInstanceOf[SagaDefinition[Any, Any, Any]]
            defAny.argsCodec.decode(argsBytes) match {
              case Failure(ex) =>
                context.log.warn(s"StartSaga rejected for $transactionId: args decode failed: ${ex.getMessage}")
                startReply.foreach(_ ! MaterializeFailed)
                Effect.none
              case Success(args) =>
                defAny.preCheck(args) match {
                  case Left(e) =>
                    val (code, message) = defAny.errorText(e)
                    startReply.foreach(_ ! PreCheckFailed(code, message))
                    Effect.none
                  case Right(_) =>
                    defAny.expand(args) match {
                      case Failure(ex) =>
                        context.log.error(s"StartSaga materialization failed for $transactionId: ${ex.getMessage}")
                        startReply.foreach(_ ! MaterializeFailed)
                        Effect.none
                      case Success(steps) =>
                        startFreshTransaction(context, timers, transactionId, definitionName, definitionVersion, argsBytes, argsHash, steps, reqTraceId, singleStep, startReply, completionReply, globalTimeout, stepExecutorBehavior, cache)
                    }
                }
            }
        }
      case _ =>
        val sameKey = state.definitionName.contains(definitionName) &&
          state.definitionVersion.contains(definitionVersion) &&
          state.argsHash.contains(argsHash)
        if (!sameKey) {
          startReply.foreach(_ ! ConflictingArgs)
          Effect.none
        } else state.status match {
          case InProgress | Compensating | Suspended =>
            startReply.foreach(_ ! AlreadyRunning(coarseSnapshot(state)))
            Effect.none
          case Completed =>
            startReply.foreach(_ ! AlreadyFinished(successful = true, state.failReason, stepSpecSnapshots(state, successful = true)))
            Effect.none
          case Failed =>
            startReply.foreach(_ ! AlreadyFinished(successful = false, state.failReason, stepSpecSnapshots(state, successful = false)))
            Effect.none
          case Created =>
            Effect.none // unreachable: handled by the outer Created branch
        }
    }
  }

  private def startFreshTransaction(
                                     context: ActorContext[Command],
                                     timers: akka.actor.typed.scaladsl.TimerScheduler[Command],
                                     transactionId: String,
                                     definitionName: String,
                                     definitionVersion: Int,
                                     argsBytes: Array[Byte],
                                     argsHash: String,
                                     steps: List[SagaTransactionStep[Any, Any, Any]],
                                     reqTraceId: String,
                                     singleStep: Boolean,
                                     startReply: Option[ActorRef[SagaStartReply]],
                                     completionReply: Option[ActorRef[TransactionResult]],
                                     globalTimeout: FiniteDuration,
                                     stepExecutorBehavior: String => Behavior[StepExecutor.Command],
                                     cache: MaterializedSteps
                                   )(implicit ec: ExecutionContext, timeout: Timeout): Effect[Event, State] = {
    val traceId = if (reqTraceId.isEmpty) transactionId else reqTraceId
    val descriptors = steps.map(StepDescriptor.of)
    cache.put(steps) // pre-materialization: the primary path, avoids group re-runs after recovery
    timers.startSingleTimer(TransactionTimeoutKey, TransactionTimeout, globalTimeout)

    Effect.persist[Event, State](TransactionStarted(transactionId, definitionName, definitionVersion, argsBytes, argsHash, descriptors, traceId, singleStep))
      .thenRun { (stateNew: State) =>
        startReply.foreach(_ ! Started)
        context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(SagaProgressEvent.TransactionStarted(transactionId, steps.map(_.stepId), traceId))
        if (singleStep) {
          context.self ! TransactionPaused(transactionId, traceId)
        } else {
          executePhase(context, stateNew, stepExecutorBehavior, cache, completionReply)
        }
      }
  }

  // ============================================================
  // Status snapshots
  // ============================================================

  private def handleGetTransactionStatus(
                                          context: ActorContext[Command],
                                          state: State,
                                          cache: MaterializedSteps,
                                          txId: String,
                                          replyTo: ActorRef[Option[StatusSnapshot]]
                                        )(implicit ec: ExecutionContext, timeout: Timeout): Effect[Event, State] = {
    import akka.actor.typed.scaladsl.AskPattern._
    if (state.transactionId.isEmpty) {
      replyTo ! None // empty entity pulled up by the query itself; no events persisted
      Effect.none
    } else {
      ensureMaterialized(cache, state) match {
        case Failure(ex) =>
          replyTo ! Some(coarseSnapshot(state).copy(failReason = Some(s"materialize failed: ${ex.getMessage}")))
          Effect.none
        case Success(_) =>
          val liveSteps = cache.stepsFor(state.currentPhase, state.currentStepGroup)
          if (liveSteps.isEmpty) {
            replyTo ! Some(coarseSnapshot(state))
            Effect.none
          } else {
            implicit val scheduler: Scheduler = context.system.scheduler
            // Only live executors contribute to the rich overlay: a stopped executor's truth is
            // already journaled in coordinator state (stepOutcomes / manuallyFixed) — never
            // fabricate a placeholder state (it used to surface as a bogus "Created" status).
            val statesFuture: Future[List[StepExecutor.State[Any, Any, Any]]] = Future.sequence(liveSteps.map { step =>
              val name = executorName(state.transactionId.getOrElse(""), step)
              val live: Future[Option[StepExecutor.State[Any, Any, Any]]] = context.child(name).map(_.asInstanceOf[ActorRef[StepExecutor.Command]]) match {
                case Some(exec) =>
                  exec.ask((ref: ActorRef[StepExecutor.State[Any, Any, Any]]) => StepExecutor.QueryStatus[Any, Any, Any](ref))(timeout, scheduler)
                    .map(Some(_): Option[StepExecutor.State[Any, Any, Any]])
                    .recover { case _ => None }
                case None =>
                  Future.successful(None)
              }
              live
            }).map(_.flatten)
            context.pipeToSelf(statesFuture) {
              case Success(states) => StatusCollected(states, replyTo)
              case Failure(_)      => StatusCollected(Nil, replyTo)
            }
            Effect.none
          }
      }
    }
  }

  /** Coarse snapshot: everything derivable from persisted coordinator state.
    * Synchronous — usable inside command handlers (no child asks). */
  private def coarseSnapshot(state: State): StatusSnapshot = {
    val compensating = state.currentPhase == CompensatePhase
    def stepStatus(d: StepDescriptor): String = {
      // journaled facts first: operator manual fixes override earlier failed outcomes;
      // per-step outcomes beat heuristics, which only fill the gaps
      val journaled =
        if (state.manuallyFixed.contains((d.stepId, d.phase))) Some("Succeeded")
        else state.stepOutcomes.get((d.stepId, d.phase)).map(_.status)
      journaled.getOrElse {
        if (state.status == Completed) "Succeeded"
        else if (state.status == Failed) "Unknown"
        else if (phaseRank(d.phase) < phaseRank(state.currentPhase)) "Succeeded"
        else if (d.phase == state.currentPhase) {
          val groups = state.steps.filter(_.phase == d.phase).map(_.stepGroup).distinct.sorted
          if (compensating) { if (d.stepGroup > state.currentStepGroup) "Succeeded" else "Unknown" }
          else if (d.stepGroup < state.currentStepGroup && groups.nonEmpty) "Succeeded"
          else "Unknown"
        } else "Unknown"
      }
    }
    StatusSnapshot(
      transactionId = state.transactionId.getOrElse(""),
      definitionName = state.definitionName.getOrElse(""),
      definitionVersion = state.definitionVersion.getOrElse(0),
      traceId = state.traceId,
      status = state.status.toString,
      currentPhase = state.currentPhase.toString,
      currentStepGroup = state.currentStepGroup,
      isPaused = state.isPaused,
      singleStep = state.singleStep,
      failReason = state.failReason,
      steps = state.steps.map(d => StepSpecSnapshot(
        stepId = d.stepId, phase = d.phase.toString, participantName = d.participantName,
        stepGroup = d.stepGroup, maxRetries = d.maxRetries, timeoutInMillis = d.timeoutDuration.toMillis,
        retryWhenRecoveredOngoing = d.retryWhenRecoveredOngoing, status = stepStatus(d)))
    )
  }

  /** Rich snapshot: live executor states for the current position overlaid on the coarse base. */
  private def richSnapshot(state: State, states: List[StepExecutor.State[Any, Any, Any]]): StatusSnapshot = {
    val base = coarseSnapshot(state)
    val liveByKey = states.flatMap(s => s.stepDescriptor.map(d => (d.stepId, d.phase.toString) -> s)).toMap
    val steps = base.steps.map { spec =>
      liveByKey.get((spec.stepId, spec.phase)) match {
        case Some(live) => spec.copy(status = live.status.toString, retries = live.retries,
          error = live.lastError.map(e => ErrorInfo(e.message, e.getClass.getSimpleName, e.isInstanceOf[RetryableFailure])))
        case None => spec
      }
    }
    base.copy(steps = steps)
  }

  private def stepSpecSnapshots(state: State, successful: Boolean): List[StepSpecSnapshot] =
    state.steps.map { d =>
      val journaled =
        if (state.manuallyFixed.contains((d.stepId, d.phase))) Some("Succeeded")
        else state.stepOutcomes.get((d.stepId, d.phase)).map(_.status)
      val status = journaled.getOrElse {
        if (successful && d.phase != CompensatePhase) "Succeeded"
        else if (!successful && d.phase == CompensatePhase) "Succeeded"
        else "Unknown"
      }
      StepSpecSnapshot(
        stepId = d.stepId, phase = d.phase.toString, participantName = d.participantName,
        stepGroup = d.stepGroup, maxRetries = d.maxRetries, timeoutInMillis = d.timeoutDuration.toMillis,
        retryWhenRecoveredOngoing = d.retryWhenRecoveredOngoing, status = status)
    }

  private def phaseRank(p: TransactionPhase): Int = p match {
    case PreparePhase    => 0
    case CommitPhase     => 1
    case CompensatePhase => 2
  }

  // ============================================================
  // Phase progression
  // ============================================================

  private def handlePhaseCompletion(
                                     context: ActorContext[Command],
                                     state: State,
                                     phase: TransactionPhase,
                                     results: List[Either[RetryableOrNotException, Any]],
                                     outcomes: List[StepOutcome],
                                     stepExecutorBehavior: String => Behavior[StepExecutor.Command],
                                     cache: MaterializedSteps,
                                     completionReply: Option[ActorRef[TransactionResult]]
                                   )(implicit ec: ExecutionContext, timeout: Timeout): Effect[Event, State] = {

    val groupsInPhase = state.steps.filter(_.phase == phase).map(_.stepGroup).distinct.sorted

    phase match {
      case PreparePhase =>
        val nextGroupOpt = groupsInPhase.find(_ > state.currentStepGroup)
        nextGroupOpt match {
          case Some(_) =>
             Effect.persist(StepGroupSucceeded(PreparePhase, state.currentStepGroup, outcomes)).thenRun { (stateNew: State) =>
                if (state.singleStep) {
                   context.self ! TransactionPaused(stateNew.transactionId.get, stateNew.traceId)
                } else {
                   context.self ! ProceedNextGroup(completionReply)
                }
             }
          case None => // All groups in Prepare finished �?the commit steps must be materializable
            ensureMaterialized(cache, state) match {
              case Failure(ex) => suspendEffect(context, state, s"materialize failed: ${ex.getMessage}", completionReply)
              case Success(_) =>
                val persistEffect = Effect.persist[Event, State](PhaseSucceeded(PreparePhase, outcomes))
                if (state.singleStep) {
                   persistEffect.thenRun { (stateNew: State) =>
                      context.self ! TransactionPaused(stateNew.transactionId.get, stateNew.traceId)
                   }
                } else {
                   persistEffect.thenRun { (stateNew: State) => executePhase(context, stateNew, stepExecutorBehavior, cache, completionReply) }
                }
            }
        }

      case CommitPhase =>
        val nextGroupOpt = groupsInPhase.find(_ > state.currentStepGroup)
        nextGroupOpt match {
           case Some(_) =>
              Effect.persist(StepGroupSucceeded(CommitPhase, state.currentStepGroup)).thenRun { (_: State) =>
                 context.self ! ProceedNextGroup(completionReply)
              }
           case None =>
              Effect.persist(
                List(
                  PhaseSucceeded(CommitPhase, outcomes),
                  TransactionCompleted(state.transactionId.get)
                )
              ).thenRun((stateNew: State) => {
                context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(SagaProgressEvent.TransactionCompleted(state.transactionId.get, stateNew.traceId))
                completionReply.foreach(_ ! TransactionResult(successful = true, coarseSnapshot(stateNew)))
              })
               .thenStop()
        }

      case CompensatePhase =>
        val nextGroupOpt = groupsInPhase.reverse.find(_ < state.currentStepGroup)
        nextGroupOpt match {
           case Some(_) =>
              Effect.persist(StepGroupSucceeded(CompensatePhase, state.currentStepGroup, outcomes)).thenRun { (_: State) =>
                 context.self ! ProceedNextGroup(completionReply)
              }
           case None =>
              Effect.persist(
                List(
                  PhaseSucceeded(CompensatePhase, outcomes),
                  TransactionFailed(state.transactionId.get, "transaction failed but compensated")
                )
              ).thenRun((stateNew: State) => {
                context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(SagaProgressEvent.TransactionFailed(state.transactionId.get, "transaction failed but compensated", stateNew.traceId))
                completionReply.foreach(_ ! TransactionResult(successful = false, coarseSnapshot(stateNew), "transaction failed but compensated"))
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
                                  outcomes: List[StepOutcome],
                                  stepExecutorBehavior: String => Behavior[StepExecutor.Command],
                                  cache: MaterializedSteps,
                                  completionReply: Option[ActorRef[TransactionResult]]
                                )(implicit ec: ExecutionContext, timeout: Timeout): Effect[Event, State] = {
    if (phase != CompensatePhase) {
      // The compensate steps are needed next — materialize in this command body.
      ensureMaterialized(cache, state) match {
        case Failure(ex) =>
          // Keep the original business failure reason and return to the correct compensation
          // recovery point on resolve (double-event, mirroring the existing compensate branch).
          val reason = s"${error.message}; materialize failed: ${ex.getMessage}"
          Effect
            .persist(PhaseFailed(phase, outcomes), TransactionSuspended(state.transactionId.get, reason))
            .thenRun { (stateNew: State) =>
              context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(SagaProgressEvent.TransactionSuspended(state.transactionId.get, reason, stateNew.traceId))
              completionReply.foreach(_ ! TransactionResult(successful = false, coarseSnapshot(stateNew), reason))
            }
            .thenStop()
        case Success(_) =>
          val persistEffect = Effect.persist[Event, State](PhaseFailed(phase, outcomes))
          if (state.singleStep) {
             persistEffect.thenRun { (stateNew: State) =>
                context.self ! TransactionPaused(stateNew.transactionId.get, stateNew.traceId)
             }
          } else {
             persistEffect.thenRun { (stateNew: State) =>
                executePhase(context, stateNew, stepExecutorBehavior, cache, completionReply)
             }
          }
      }
    } else {
      val reason = s"Phase $phase failed with error: ${error.message}"
      Effect
        .persist(PhaseFailed(phase, outcomes), TransactionSuspended(state.transactionId.get, reason))
        .thenRun { (stateNew: State) =>
          context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(SagaProgressEvent.TransactionSuspended(state.transactionId.get, reason, stateNew.traceId))
          completionReply.foreach(_ ! TransactionResult(successful = false, coarseSnapshot(stateNew), reason))
        }
        .thenStop()
    }
  }

  private def suspendEffect(
                             context: ActorContext[Command],
                             state: State,
                             reason: String,
                             completionReply: Option[ActorRef[TransactionResult]]
                           )(implicit ec: ExecutionContext, timeout: Timeout): Effect[Event, State] =
    Effect
      .persist(TransactionSuspended(state.transactionId.get, reason))
      .thenRun { (stateNew: State) =>
        context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(SagaProgressEvent.TransactionSuspended(state.transactionId.get, reason, stateNew.traceId))
        completionReply.foreach(_ ! TransactionResult(successful = false, coarseSnapshot(stateNew), reason))
      }
      .thenStop()

  private def executorName(transactionId: String, step: SagaTransactionStep[_, _, _]): String =
    s"$transactionId-${step.stepId}-${step.phase}"

  private def findOrSpawn(context: ActorContext[Command], stepExecutorBehavior: String => Behavior[StepExecutor.Command], name: String): ActorRef[StepExecutor.Command] =
    context.child(name).map(_.asInstanceOf[ActorRef[StepExecutor.Command]])
      .getOrElse(context.spawn(stepExecutorBehavior(name), name))

  /** Best-effort executor notify so the executor journals ManualFixCompleted and the UI sees
    * the isManual progress event. NOT load-bearing for recovery correctness: the authoritative
    * manual-fix record is the coordinator's own StepManuallyFixed event. */
  private def notifyExecutorManualFix(
                                       context: ActorContext[Command],
                                       state: State,
                                       stepExecutorBehavior: String => Behavior[StepExecutor.Command],
                                       cache: MaterializedSteps,
                                       stepId: String,
                                       phase: TransactionPhase
                                     ): Unit =
    state.transactionId match {
      case Some(tid) =>
        cache.get(stepId, phase) match {
          case Some(_) =>
            findOrSpawn(context, stepExecutorBehavior, s"$tid-$stepId-$phase") ! StepExecutor.ManualFix(None)
            ()
          case None =>
            // The step may not be materialized (e.g. executor never attached); attempt lazy materialization.
            materialize(state) match {
              case Success(steps) =>
                cache.put(steps)
                cache.get(stepId, phase) match {
                  case Some(_) =>
                    findOrSpawn(context, stepExecutorBehavior, s"$tid-$stepId-$phase") ! StepExecutor.ManualFix(None)
                    ()
                  case None =>
                    context.log.warn(s"Step $stepId not found in phase $phase for transaction $tid")
                }
              case Failure(ex) =>
                context.log.warn(s"ManualFixStep for $stepId/$phase could not materialize: ${ex.getMessage}")
            }
        }
      case None => context.log.warn("ManualFixStep received but transactionId is missing")
    }

  private def executePhase(
                                  context: ActorContext[Command],
                                  state: State,
                                  stepExecutorBehavior: String => Behavior[StepExecutor.Command],
                                  cache: MaterializedSteps,
                                  completionReply: Option[ActorRef[TransactionResult]]
                                )(implicit ec: ExecutionContext, askTimeout: Timeout): Unit = {

    val stepsInPhase: List[SagaTransactionStep[Any, Any, Any]] = state.steps
      .filter(s => s.phase == state.currentPhase && s.stepGroup == state.currentStepGroup)
      .filterNot(d => state.manuallyFixed.contains((d.stepId, d.phase)))
      .flatMap(d => cache.get(d.stepId, d.phase))

    if (stepsInPhase.isEmpty) {
       context.log.info(s"[TraceID: ${state.traceId}] No steps found for phase ${state.currentPhase} and group ${state.currentStepGroup}, completing group/phase...")
       context.self ! PhaseCompleted(state.currentPhase, Nil, Nil, completionReply)
       return
    }

    import akka.actor.typed.scaladsl.AskPattern._
    implicit val scheduler: Scheduler = context.system.scheduler
    val log = context.log

    val futureResults: Future[List[StepResult[Any, Any, Any]]] = Future.sequence(
      stepsInPhase.map { step =>
        val stepExecutor = findOrSpawn(context, stepExecutorBehavior, executorName(state.transactionId.get, step))
        stepExecutor.ask((ref: ActorRef[StepResult[Any, Any, Any]]) => StepExecutor.Attach[Any, Any, Any](state.transactionId.get, step, Some(ref), state.traceId))(askTimeout, scheduler)
          .mapTo[StepResult[Any, Any, Any]]
          .recoverWith {
            case _: java.util.concurrent.TimeoutException | _: akka.pattern.AskTimeoutException =>
              log.warn(s"[TraceID: ${state.traceId}] Coordinator ask timed out for step ${step.stepId}, querying status...")
              def pollStatus(retries: Int): Future[StepResult[Any, Any, Any]] = {
                log.info(s"[TraceID: ${state.traceId}] Polling status for step ${step.stepId}, retries left: $retries")
                stepExecutor.ask((ref: ActorRef[StepExecutor.State[Any, Any, Any]]) => StepExecutor.QueryStatus[Any, Any, Any](ref))(askTimeout, scheduler)
                  .flatMap { executorState =>
                    executorState.status match {
                      case StepExecutor.Succeed =>
                        executorState.result match {
                          case Some(res) =>
                            Future.successful(StepExecutor.StepCompleted[Any, Any, Any](state.transactionId.get, step.stepId, res))
                          case None =>
                            log.error(s"[TraceID: ${state.traceId}] Step ${step.stepId} succeeded but no result found")
                            Future.failed(new RuntimeException("Step succeeded but no result found"))
                        }                      case StepExecutor.Failed =>
                        Future.successful(StepExecutor.StepFailed[Any, Any, Any](state.transactionId.get, step.stepId, executorState.lastError.getOrElse(NonRetryableFailure("Unknown error"))))
                      case _ if retries > 0 =>
                        val promise = scala.concurrent.Promise[StepResult[Any, Any, Any]]()
                        context.system.scheduler.scheduleOnce(2.seconds, new Runnable {
                          override def run(): Unit = promise.completeWith(pollStatus(retries - 1))
                        })
                        promise.future
                      case _ =>
                        Future.successful(StepExecutor.StepFailed[Any, Any, Any](state.transactionId.get, step.stepId, RetryableFailure(s"Step still ongoing after status queries")))
                    }
                  }
                  .recover {
                    case ex: Throwable =>
                      log.warn(s"[TraceID: ${state.traceId}] Coordinator query status failed: ${ex.getMessage}")
                      StepExecutor.StepFailed[Any, Any, Any](state.transactionId.get, step.stepId, NonRetryableFailure(s"Coordinator query status failed: ${ex.getMessage}"))
                  }
              }
              pollStatus(3)
            case ex: Throwable =>
              log.error(s"[TraceID: ${state.traceId}] Coordinator ask failed with unexpected exception: ${ex.getClass.getName} - ${ex.getMessage}")
              Future.successful(StepExecutor.StepFailed[Any, Any, Any](state.transactionId.get, step.stepId, NonRetryableFailure(s"Coordinator ask failed: ${ex.getMessage}")))
          }
      }
    )

    futureResults.foreach(stepResults => {

      // outcome per dispatched step, position-aligned with stepsInPhase (Future.sequence preserves order)
      val outcomes: List[StepOutcome] = stepsInPhase.zip(stepResults).map { case (step, result) =>
        result match {
          case _: StepCompleted[_, _, _] => StepOutcome(step.stepId, step.phase, "Succeeded")
          case _: StepFailed[_, _, _]    => StepOutcome(step.stepId, step.phase, "Failed")
        }
      }

      val positiveResults = stepResults.foldLeft[List[Either[RetryableOrNotException, Any]]](Nil)((acc, result) => result match {
        case StepCompleted(tid, sid, r) => Right(r) :: acc
        case StepFailed(tid, sid, e) => Left(NonRetryableFailure(e.toString)) :: acc
      })

      stepResults.find(_.isInstanceOf[StepFailed[_, _, _]]).map(firstError => {
        context.self ! PhaseFailure(state.currentPhase, NonRetryableFailure(firstError.toString), outcomes, completionReply)
      }).getOrElse({
        context.self ! PhaseCompleted(state.currentPhase, positiveResults, outcomes, completionReply)
      })

    })
  }

  def eventHandler: (State, Event) => State = { (state, event) =>
    event match {
      case TransactionStarted(transactionId, definitionName, definitionVersion, argsBytes, argsHash, steps, traceId, singleStep) =>
        val firstGroupInPrepare = steps.filter(_.phase == PreparePhase).map(_.stepGroup).distinct.sorted.headOption.getOrElse(1)
        state.copy(
          transactionId = Some(transactionId),
          definitionName = Some(definitionName),
          definitionVersion = Some(definitionVersion),
          argsBytes = Some(argsBytes),
          argsHash = Some(argsHash),
          steps = steps,
          status = InProgress,
          traceId = traceId,
          singleStep = singleStep,
          currentStepGroup = firstGroupInPrepare)

      case TransactionPaused(_, _) =>
        state.copy(isPaused = true)

      case TransactionResumed(_, _) =>
        state.copy(isPaused = false)

      case PhaseFailed(phase, outcomes) =>
        val merged = state.stepOutcomes ++ outcomes.map(o => ((o.stepId, o.phase), o))
        val maxGroupInCompensate = state.steps.filter(_.phase == CompensatePhase).map(_.stepGroup).distinct.sorted.lastOption.getOrElse(1)
        phase match {
          case PreparePhase => state.copy(currentPhase = CompensatePhase, status = Compensating, currentStepGroup = maxGroupInCompensate, stepOutcomes = merged)
          case CommitPhase => state.copy(currentPhase = CompensatePhase, status = Compensating, currentStepGroup = maxGroupInCompensate, stepOutcomes = merged)
          case CompensatePhase => state.copy(stepOutcomes = merged)
        }
      case StepGroupSucceeded(phase, group, outcomes) =>
        val merged = state.stepOutcomes ++ outcomes.map(o => ((o.stepId, o.phase), o))
        val groupsInPhase = state.steps.filter(_.phase == phase).map(_.stepGroup).distinct.sorted
        val nextGroup =
          if (phase == CompensatePhase) groupsInPhase.reverse.find(_ < group).getOrElse(groupsInPhase.headOption.getOrElse(1))
          else groupsInPhase.find(_ > group).getOrElse(groupsInPhase.lastOption.getOrElse(1))
        state.copy(currentStepGroup = nextGroup, stepOutcomes = merged)
      case PhaseSucceeded(phase, outcomes) =>
        val merged = state.stepOutcomes ++ outcomes.map(o => ((o.stepId, o.phase), o))
        phase match {
          case PreparePhase =>
            val firstGroupInCommit = state.steps.filter(_.phase == CommitPhase).map(_.stepGroup).distinct.sorted.headOption.getOrElse(1)
            state.copy(currentPhase = CommitPhase, currentStepGroup = firstGroupInCommit, stepOutcomes = merged)
          case CommitPhase => state.copy(status = Completed, stepOutcomes = merged)
          case CompensatePhase => state.copy(stepOutcomes = merged)
        }
      case StepManuallyFixed(stepId, phase) =>
        state.copy(manuallyFixed = state.manuallyFixed + ((stepId, phase)))
      case TransactionCompleted(_) =>
        state.copy(status = Completed)
      case TransactionFailed(_, reason) =>
        state.copy(status = Failed, failReason = Some(reason))
      case TransactionSuspended(_, reason) =>
        state.copy(status = Suspended, failReason = Some(reason))
      case TransactionResolved(_) =>
        val newStatus = if (state.currentPhase == CompensatePhase) Compensating else InProgress
        state.copy(status = newStatus)
      case TransactionRetried(_, phase) =>
        val firstGroupInPhase = state.steps.filter(_.phase == phase).map(_.stepGroup).distinct.sorted.headOption.getOrElse(1)
        state.copy(currentPhase = phase, status = InProgress, currentStepGroup = firstGroupInPhase)
    }
  }
}
