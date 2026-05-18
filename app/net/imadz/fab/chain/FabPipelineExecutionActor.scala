package net.imadz.fab.chain

import akka.actor.typed.scaladsl.{Behaviors, ActorContext}
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, Recovery}
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import net.imadz.fab.chain.FabScenarioPipeline.PipelineStage
import net.imadz.fab.model.FabExecutionModel.{FabDemoContext, FabDemoState}
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
 *   2. Processor calls `onPhaseComplete` for each stage → self ! `PhaseCompleted` → persist `PhaseDone`
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
 * Saga transactions use [[net.imadz.fab.service.SagaIdGenerator.generate]]
 * to produce deterministic UUID v3 transaction IDs, enabling sagas to be idempotently
 * re-attached after recovery.
 */
object FabPipelineExecutionActor {

  val EntityKey: EntityTypeKey[Command] =
    EntityTypeKey[Command]("FabPipelineExecution")

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
  private[chain] final case class PhaseCompleted(
      phase: String,
      metadata: Map[String, String]
  ) extends Command

  /** Internal: entire pipeline completed successfully. */
  private[chain] case object PipelineSucceeded extends Command

  /** Internal: pipeline failed at a given phase. */
  private[chain] final case class PipelineFailed(
      phase: String,
      reason: String
  ) extends Command

  // ---- Events ----

  final case class Started(
      scenarioId: String,
      workOrderId: String,
      stageCount: Int
  ) extends Event

  final case class PhaseDone(
      phase: String,
      timestamp: Long,
      metadata: Map[String, String]
  ) extends Event

  final case class AllCompleted(
      scenarioId: String,
      workOrderId: String
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
      completedPhases: List[String]
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
      stageResolver: String => Seq[PipelineStage]
  )(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors.setup { ctx =>
      val persistenceId = PersistenceId(EntityKey.name, entityId)

      EventSourcedBehavior[Command, Event, State](
        persistenceId = persistenceId,
        emptyState = Idle,
        commandHandler = { (state, cmd) =>
          internalCommandHandler(ctx, entityId, state, cmd, contextFactory, stateFactory, stageResolver)
        },
        eventHandler = eventHandler
      ).withRecovery(Recovery.default)
        .receiveSignal {
          case (execState: Executing, RecoveryCompleted) =>
            // Crash recovery: reconstruct context + initial state, resume processing from last checkpoint
            try {
              val recoveryCtx = contextFactory(execState.scenarioId, execState.workOrderId)
              val initState = stateFactory(execState.workOrderId)
              val recoveryStages = stageResolver(execState.scenarioId)

              val processor = FabPipelineProcessor(recoveryStages, recoveryCtx,
                (phase, metadata) => ctx.self ! PhaseCompleted(phase, metadata))

              processor.resumeFromIndex(initState, execState.completedCount).onComplete {
                case Success(_) =>
                  ctx.self ! PipelineSucceeded
                case Failure(e) =>
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
      stageResolver: String => Seq[PipelineStage]
  )(implicit ec: ExecutionContext): Effect[Event, State] = {

    (state, cmd) match {

      // ---- Start execution ----
      case (Idle, StartExecution(scenarioId, workOrderId, initialState, stages, fctx, replyTo)) =>
        val event = Started(scenarioId, workOrderId, stages.size)
        Effect.persist(event).thenRun { _ =>
          val processor = FabPipelineProcessor(stages, fctx,
            (phase, metadata) => ctx.self ! PhaseCompleted(phase, metadata))

          processor.process(initialState).onComplete {
            case Success(_) =>
              ctx.self ! PipelineSucceeded
            case Failure(e) =>
              ctx.self ! PipelineFailed("pipeline", e.getMessage)
          }
          replyTo ! Accepted
        }

      // ---- Phase completed callback from processor ----
      case (_: Executing, PhaseCompleted(phase, metadata)) =>
        Effect.persist(PhaseDone(phase, System.currentTimeMillis(), metadata))

      // ---- Pipeline fully done ----
      case (_: Executing, PipelineSucceeded) =>
        val execState = state.asInstanceOf[Executing]
        Effect.persist(AllCompleted(execState.scenarioId, execState.workOrderId))

      // ---- Pipeline failed ----
      case (_: Executing, PipelineFailed(phase, reason)) =>
        Effect.persist(ExecutionFailed(phase, reason))

      // ---- Ignore late PhaseCompleted after completion ----
      case (_: Completed, PhaseCompleted(_, _)) =>
        Effect.none

      case (_: Failed, PhaseCompleted(_, _)) =>
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
      case (Idle, Started(scenarioId, workOrderId, _)) =>
        Executing(scenarioId, workOrderId, completedPhases = Nil)

      case (e: Executing, PhaseDone(phase, _, _)) =>
        e.copy(completedPhases = e.completedPhases :+ phase)

      case (e: Executing, AllCompleted(_, _)) =>
        Completed(e.scenarioId, e.workOrderId)

      case (_: Executing, ExecutionFailed(phase, reason)) =>
        Failed(phase, reason)

      // Idempotent replay: duplicate PhaseDone after AllCompleted
      case (_: Completed, PhaseDone(_, _, _)) =>
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
      stageResolver: String => Seq[PipelineStage]
  )(implicit ec: ExecutionContext): Unit = {
    sharding.init(
      Entity(EntityKey) { entityContext =>
        apply(entityContext.entityId, contextFactory, stateFactory, stageResolver)
      }
    )
  }

  /** Default stage resolver for known static scenario IDs. */
  val DefaultStageResolver: String => Seq[PipelineStage] = { scenarioId =>
    scenarioId match {
      case "send-ahead-pilot" => FabScenarioPipeline.sendAheadStages
      case "scrap-downgrade"  => FabScenarioPipeline.scrapStages
      case "sampling-demo"    => FabScenarioPipeline.samplingStages
      case "hold-release"     => FabScenarioPipeline.holdReleaseStages
      case _                  => FabScenarioPipeline.basicStages
    }
  }
}
