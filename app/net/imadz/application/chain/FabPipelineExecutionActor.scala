package net.imadz.application.chain

import akka.actor.typed.scaladsl.{Behaviors, ActorContext}
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, Recovery}
import akka.persistence.typed.{PersistenceId, RecoveryCompleted => AkkaRecoveryCompleted}
import net.imadz.application.chain.FabScenarioPipeline.PipelineStage
import net.imadz.domain.events.{FabSimulationEvent, GlobalStatusChanged, RecoveryEvent}
import net.imadz.application.chain.FabExecutionModel.{FabDemoContext, FabDemoState}
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/**
 * ===== FabPipelineExecutionActor =====
 *
 * EventSourcedBehavior wrapping [[FabPipelineProcessor]] for crash-resilient
 * Fab pipeline execution.
 *
 * == Pattern ==
 *
 * Follows the exact same EventSourcedBehavior pattern as [[net.imadz.m25.component.ChainExecutionActor]]:
 *
 *   1. `StartExecution` command → persist `Started` event → create processor → run pipeline
 *   2. Processor calls `onPhaseComplete` for each stage → self ! `PhaseCompleted` → persist `StageCompleted`
 *   3. Pipeline fully done → self ! `PipelineSucceeded` → persist `AllCompleted`
 *   4. Pipeline failure → self ! `PipelineFailed` → persist `ExecutionFailed`
 *   5. `RecoveryCompleted` → reconstruct context → resume pipeline (skip completed phases)
 *
 * == Crash Recovery ==
 *
 * Events store cursor (phase name), NOT full FabDemoState. On recovery:
 *   - State `Executing(completedPhases)` tells us which phases finished
 *   - `contextFactory` reconstructs the FabDemoContext (ActorRefs, publisher, etc.)
 *   - `stateFactory` reconstructs the initial FabDemoState
 *   - `stageResolver` resolves scenarioId → Seq[PipelineStage]
 *   - `processor.resumeFromIndex(completedCount)` skips already-completed stages
 *
 * == Deterministic Saga Re-attach ==
 *
 * Saga transactions use [[net.imadz.application.services.SagaIdGenerator.generate]]
 * to produce deterministic UUID v3 transaction IDs, enabling sagas to be idempotently
 * re-attached after recovery.
 */
object FabPipelineExecutionActor {

  val EntityKey: EntityTypeKey[Command] =
    EntityTypeKey[Command]("FabPipelineExecution")

  /** Journal tag for [[eventsByTag]] Projection subscriptions. */
  val Tag = "fab-pipeline"

  // ============================================================
  // Protocol
  // ============================================================

  sealed trait Command
  sealed trait Event
  sealed trait State
  sealed trait ExecutionReply

  /** Start pipeline execution with the given stages and context. */
  final case class StartExecution(
      scenarioId: String,
      workOrderId: String,
      initialState: FabDemoState,
      stages: Seq[PipelineStage],
      ctx: FabDemoContext,
      replyTo: ActorRef[ExecutionReply]
  ) extends Command

  /** Internal: stage completed callback from the processor. */
  final case class PhaseCompleted(
      phase: String,
      metadata: Map[String, String],
      fabState: Option[FabDemoState] = None
  ) extends Command

  /** Internal: entire pipeline completed successfully. */
  final case class PipelineSucceeded(
      finalState: FabDemoState,
      foupId: String
  ) extends Command

  /** Internal: pipeline failed at a given phase. */
  final case class PipelineFailed(
      phase: String,
      reason: String
  ) extends Command

  /** M3.5: Stop/crash the pipeline actor. Causes Behavior.stopped → sharding restarts → RecoveryCompleted. */
  final case class StopPipeline(workOrderId: String) extends Command

  /** Internal: stage started callback from the processor. */
  final case class PhaseStarting(phase: String) extends Command

  /** Internal: stage failed callback from the processor. */
  final case class PhaseFailed(phase: String, error: FabExecutionModel.StageError) extends Command

  /** Internal: intra-stage progress notification (replaces ctx.publisher(GlobalStatusChanged)). */
  final case class StageProgressEvent(status: String, detail: String, phase: String) extends Command

  // ---- Events ----

  final case class StageProgress(
      status: String,
      detail: String,
      phase: String,
      timestamp: Long
  ) extends Event

  final case class Started(
      scenarioId: String,
      workOrderId: String,
      stageCount: Int
  ) extends Event

  final case class StageStarted(
      phase: String,
      timestamp: Long
  ) extends Event

  final case class StageCompleted(
      phase: String,
      timestamp: Long,
      metadata: Map[String, String],
      fabState: Option[FabDemoState] = None
  ) extends Event

  final case class StageFailed(
      phase: String,
      error: FabExecutionModel.StageError,
      timestamp: Long
  ) extends Event

  final case class AllCompleted(
      scenarioId: String,
      workOrderId: String,
      totalWafers: Int = 0,
      passedWafers: Int = 0,
      reworkedWafers: Int = 0,
      scrappedWafers: Int = 0
  ) extends Event

  final case class ExecutionFailed(
      phase: String,
      reason: String
  ) extends Event

  // ---- States ----

  case object Idle extends State

  final case class Executing(
      scenarioId: String,
      workOrderId: String,
      completedPhases: List[String],
      stageCount: Int = 0,
      fabDemoState: Option[FabDemoState] = None
  ) extends State {
    def lastCompletedPhase: Option[String] = completedPhases.lastOption
    def completedCount: Int = completedPhases.size
  }

  final case class Completed(
      scenarioId: String,
      workOrderId: String
  ) extends State

  final case class Failed(
      phase: String,
      reason: String
  ) extends State

  // ---- Replies ----

  case object Accepted extends ExecutionReply
  final case class Rejected(reason: String) extends ExecutionReply

  // ============================================================
  // Factory
  // ============================================================

  /**
   * Create the EventSourcedBehavior for a FabPipelineExecutionActor.
   *
   * @param entityId          unique entity ID (e.g. workOrderId)
   * @param contextFactory    reconstructs FabDemoContext from (scenarioId, workOrderId) on recovery
   * @param stateFactory      reconstructs FabDemoState from workOrderId on recovery
   * @param stageResolver     resolves scenarioId → Seq[PipelineStage] for recovery
   */
  def apply(
      entityId: String,
      contextFactory: (String, String) => FabDemoContext,
      stateFactory: String => FabDemoState,
      stageResolver: String => Seq[PipelineStage],
      publisher: FabSimulationEvent => Unit = _ => ()
  )(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors.setup { ctx =>
      val persistenceId = PersistenceId(EntityKey.name, entityId)
      val startTime = System.currentTimeMillis()

      EventSourcedBehavior[Command, Event, State](
        persistenceId = persistenceId,
        emptyState = Idle,
        commandHandler = { (state, cmd) =>
          internalCommandHandler(ctx, entityId, state, cmd, contextFactory, stateFactory, stageResolver, publisher)
        },
        eventHandler = eventHandler
      ).withTagger(_ => Set(Tag))
        .withRecovery(Recovery.default)
        .snapshotWhen { (_, event, _) =>
          event match {
            case StageCompleted(phase, _, _, _) =>
              phase.startsWith("Measure") || phase == "Classify" || phase == "M35ClassifyWithOcap"
            case _ => false
          }
        }
        .receiveSignal {
          case (execState: Executing, AkkaRecoveryCompleted) =>
            // Crash recovery: reconstruct context + initial state, resume processing from last checkpoint.
            // RECOVERING/RECOVERED RecoveryEvent + GlobalStatusChanged are demo UX affordance.
            // Published directly via publisher (not journal) — production recovery is transparent.
            val recStart = System.currentTimeMillis()
            publisher(GlobalStatusChanged("RECOVERING", s"Crash recovery for ${execState.workOrderId}", "Recovery"))
            publisher(RecoveryEvent(
              execState.workOrderId, "RECOVERING",
              eventsReplayed = execState.completedPhases.size,
              phasesSkipped = execState.completedCount,
              recoveryTimeMs = recStart - startTime,
              detail = s"Recovering: ${execState.completedCount} phases completed, resuming from phase ${execState.completedCount}"))
            try {
              val recoveryCtx = contextFactory(execState.scenarioId, execState.workOrderId)
              recoveryCtx.stageProgressFn = (status, detail, phase) =>
                ctx.self ! StageProgressEvent(status, detail, phase)
              val initState = execState.fabDemoState.getOrElse(stateFactory(execState.workOrderId))
              val recoveryStages = stageResolver(execState.scenarioId)

              val processor = FabPipelineProcessor(recoveryStages, recoveryCtx,
                phase => ctx.self ! PhaseStarting(phase),
                (phase, metadata, fabState) => ctx.self ! PhaseCompleted(phase, metadata, fabState),
                (phase, error) => ctx.self ! PhaseFailed(phase, error))

              processor.resumeFromIndex(initState, execState.completedCount).onComplete {
                case Success(finalState) =>
                  publisher(RecoveryEvent(
                    execState.workOrderId, "RECOVERED",
                    eventsReplayed = execState.completedPhases.size,
                    phasesSkipped = execState.completedCount,
                    recoveryTimeMs = System.currentTimeMillis() - recStart,
                    detail = s"Recovery succeeded: ${execState.completedCount} phases skipped, resuming pipeline"))
                  ctx.self ! PipelineSucceeded(finalState, recoveryCtx.foupId)
                case Failure(e) =>
                  publisher(RecoveryEvent(
                    execState.workOrderId, "RECOVERY_FAILED",
                    eventsReplayed = execState.completedPhases.size,
                    phasesSkipped = execState.completedCount,
                    recoveryTimeMs = System.currentTimeMillis() - recStart,
                    detail = s"Recovery failed: ${e.getMessage}"))
                  ctx.self ! PipelineFailed("recovery", e.getMessage)
              }(ec)

            } catch {
              case e: Exception =>
                ctx.self ! PipelineFailed("recovery", s"Recovery failed: ${e.getMessage}")
            }

          case _ => ()
        }
    }
  }

  // ============================================================
  // Internal Command Handler
  // ============================================================

  private def internalCommandHandler(
      ctx: ActorContext[Command],
      entityId: String,
      state: State,
      cmd: Command,
      contextFactory: (String, String) => FabDemoContext,
      stateFactory: String => FabDemoState,
      stageResolver: String => Seq[PipelineStage],
      publisher: FabSimulationEvent => Unit
  )(implicit ec: ExecutionContext): Effect[Event, State] = {

    (state, cmd) match {

      // ---- Start execution ----
      case (Idle, StartExecution(scenarioId, workOrderId, initialState, stages, fctx, replyTo)) =>
        val event = Started(scenarioId, workOrderId, stages.size)
        Effect.persist(event).thenRun { _ =>
          fctx.stageProgressFn = (status, detail, phase) =>
            ctx.self ! StageProgressEvent(status, detail, phase)
          val processor = FabPipelineProcessor(stages, fctx,
            phase => ctx.self ! PhaseStarting(phase),
            (phase, metadata, fabState) => ctx.self ! PhaseCompleted(phase, metadata, fabState),
            (phase, error) => ctx.self ! PhaseFailed(phase, error))

          processor.process(initialState).onComplete {
            case Success(finalState) =>
              ctx.self ! PipelineSucceeded(finalState, fctx.foupId)
            case Failure(e) =>
              ctx.self ! PipelineFailed("pipeline", e.getMessage)
          }
          replyTo ! Accepted
        }

      // ---- Phase starting callback from processor ----
      case (_: Executing, PhaseStarting(phase)) =>
        Effect.persist(StageStarted(phase, System.currentTimeMillis()))

      // ---- Phase completed callback from processor ----
      case (es: Executing, PhaseCompleted(phase, metadata, fabState)) =>
        Effect.persist(StageCompleted(phase, System.currentTimeMillis(), metadata, fabState))

      // ---- Phase failed callback from processor ----
      case (_: Executing, PhaseFailed(phase, error)) =>
        Effect.persist(StageFailed(phase, error, System.currentTimeMillis()))

      // ---- Intra-stage progress (replaces ctx.publisher(GlobalStatusChanged)) ----
      case (_: Executing, StageProgressEvent(status, detail, phase)) =>
        Effect.persist(StageProgress(status, detail, phase, System.currentTimeMillis()))

      // ---- Pipeline fully done ----
      case (_: Executing, PipelineSucceeded(finalState, foupId)) =>
        val execState = state.asInstanceOf[Executing]
        val wafers = finalState.wafers.values
        val passCount = wafers.count(w => w.classification.contains("PASS"))
        val scrapCount = wafers.count(w => w.classification.contains("SCRAP"))
        val reworkCount = wafers.count(_.reworkCount > 0)
        Effect.persist(AllCompleted(execState.scenarioId, execState.workOrderId,
          totalWafers = wafers.size, passedWafers = passCount,
          reworkedWafers = reworkCount, scrappedWafers = scrapCount))

      // ---- Pipeline failed ----
      case (_: Executing, PipelineFailed(phase, reason)) =>
        Effect.persist(ExecutionFailed(phase, reason))

      // ---- M3.5: Stop/crash the pipeline ----
      case (es: Executing, StopPipeline(woId)) =>
        Effect.none.thenRun { _ =>
          publisher(RecoveryEvent(woId, "CRASH_DETECTED",
            eventsReplayed = es.completedPhases.size, phasesSkipped = es.completedCount,
            recoveryTimeMs = System.currentTimeMillis(),
            detail = s"Actor crash for workOrder $woId (${es.completedCount} phases completed)"))
          ctx.log.warn(s"[FabPipelineExecutionActor:$entityId] Crash injected, stopping actor")
          throw new RuntimeException(s"Pipeline crash injected for workOrder $woId")
        }

      case (Idle, StopPipeline(woId)) =>
        Effect.none.thenRun { _ =>
          publisher(RecoveryEvent(woId, "CRASH_DETECTED", 0, 0, System.currentTimeMillis(),
            s"Actor crash injected (idle state)"))
          ctx.log.warn(s"[FabPipelineExecutionActor:$entityId] Crash injected in Idle state")
          throw new RuntimeException(s"Crash injected for actor $entityId")
        }

      // ---- Ignore late phase callbacks after completion ----
      case (_: Completed, PhaseCompleted(_, _, _)) =>
        Effect.none

      case (_: Completed, PhaseStarting(_)) =>
        Effect.none

      case (_: Completed, PhaseFailed(_, _)) =>
        Effect.none

      case (_: Failed, PhaseCompleted(_, _, _)) =>
        Effect.none

      case (_: Failed, PhaseStarting(_)) =>
        Effect.none

      case (_: Failed, PhaseFailed(_, _)) =>
        Effect.none

      case (_: Completed, StageProgressEvent(_, _, _)) =>
        Effect.none

      case (_: Failed, StageProgressEvent(_, _, _)) =>
        Effect.none

      // ---- Already idle/completed/failed, reject new Start ----
      case (s, StartExecution(_, _, _, _, _, replyTo)) =>
        ctx.log.warn(
          s"[FabPipelineExecutionActor:$entityId] Rejecting StartExecution in state ${s.getClass.getSimpleName}"
        )
        replyTo ! Rejected(s"Already in state ${s.getClass.getSimpleName}")
        Effect.none

      case _ =>
        Effect.unhandled
    }
  }

  // ============================================================
  // Event Handler
  // ============================================================

  private val eventHandler: (State, Event) => State = { (state, event) =>
    (state, event) match {
      case (Idle, Started(scenarioId, workOrderId, stageCount)) =>
        Executing(scenarioId, workOrderId, completedPhases = Nil, stageCount = stageCount)

      case (e: Executing, StageStarted(_, _)) =>
        e

      case (e: Executing, StageCompleted(phase, _, _, Some(fabState))) =>
        e.copy(completedPhases = e.completedPhases :+ phase, fabDemoState = Some(fabState))

      case (e: Executing, StageCompleted(phase, _, _, None)) =>
        e.copy(completedPhases = e.completedPhases :+ phase)

      case (e: Executing, StageFailed(_, _, _)) =>
        e

      case (e: Executing, StageProgress(_, _, _, _)) =>
        e

      case (e: Executing, AllCompleted(_, _, _, _, _, _)) =>
        Completed(e.scenarioId, e.workOrderId)

      case (_: Executing, ExecutionFailed(phase, reason)) =>
        Failed(phase, reason)

      // Idempotent replay: duplicate events after AllCompleted
      case (_: Completed, StageStarted(_, _)) =>
        state

      case (_: Completed, StageCompleted(_, _, _, _)) =>
        state

      case (_: Completed, StageFailed(_, _, _)) =>
        state

      case (_: Completed, StageProgress(_, _, _, _)) =>
        state

      case _ =>
        state
    }
  }

  // ============================================================
  // Public: convenience factory for sharding registration
  // ============================================================


  def init(
      sharding: ClusterSharding,
      contextFactory: (String, String) => FabDemoContext,
      stateFactory: String => FabDemoState,
      stageResolver: String => Seq[PipelineStage],
      publisher: FabSimulationEvent => Unit = _ => ()
  )(implicit ec: ExecutionContext): Unit = {
    sharding.init(
      Entity(EntityKey) { entityContext =>
        apply(entityContext.entityId, contextFactory, stateFactory, stageResolver, publisher)
      }
    )
  }

  /** Default stage resolver delegates to [[FabScenarioPipeline.resolveStages]]. */
  val DefaultStageResolver: String => Seq[PipelineStage] = FabScenarioPipeline.resolveStages
}
