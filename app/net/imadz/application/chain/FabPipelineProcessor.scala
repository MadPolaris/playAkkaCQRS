package net.imadz.application.chain

import net.imadz.domain.events._
import net.imadz.application.chain.FabScenarioPipeline.{PipelineStage, _}
import net.imadz.application.chain.FabExecutionModel.{FabDemoContext, FabDemoState, StageError, StageFailedException}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/**
 * Queue-based pipeline processor that supports stage-level completion tracking,
 * runtime dynamic injection (injectHead/appendTail), and crash recovery.
 *
 * Designed to be wrapped by [[FabPipelineExecutionActor]] for EventSourced persistence.
 * Each stage lifecycle event (start/complete/fail) triggers a callback which signals
 * the actor to persist the corresponding domain event.
 *
 * Stage name derivation converts each [[PipelineStage]] variant to a stable string
 * cursor suitable for use as an event-sourcing phase cursor. Repeated variants
 * (e.g. multiple `Branch` stages) are disambiguated with their queue position so that
 * journal cursors stay unique.
 *
 * P0: every stage boundary checks [[FabDemoContext]].runToken — a superseded run
 * (crash recovery started a newer generation) terminates silently instead of racing
 * the recovered run.
 */
class FabPipelineProcessor(
  ctx: FabDemoContext,
  onPhaseStart: String => Unit,
  onPhaseComplete: (String, Map[String, String], Option[FabDemoState]) => Unit,
  onOcapResolved: (String, StageError, FabDemoState) => Unit,
  onPhaseFailed: (String, StageError) => Unit
) {

  /** Queue entries carry a stable position so repeated stage variants get unique cursors. */
  private var entries: Vector[(PipelineStage, Int)] = Vector.empty
  private var nextPos: Int = 0

  /** Initialise the processor with the full stage list. */
  def initialize(stages: Seq[PipelineStage]): Unit = {
    entries = stages.map(s => (s, nextPos)).toVector
    nextPos += stages.size
  }

  /** Prepend stages to the head of the queue (Branch / OCAP runtime weaving). */
  def injectHead(stages: Seq[PipelineStage]): Unit = {
    entries = stages.map(s => (s, nextPos)).toVector ++ entries
    nextPos += stages.size
  }

  /** Append stages to the tail of the queue. */
  def appendTail(stages: Seq[PipelineStage]): Unit = {
    entries = entries ++ stages.map(s => (s, nextPos)).toVector
    nextPos += stages.size
  }

  /** Current queue size. */
  def pendingCount: Int = entries.size

  // ====================================================================
  // Stage name derivation
  // ====================================================================

  /** Derive a stable, human-readable phase name from a PipelineStage. */
  def stageName(stage: PipelineStage): String = stage match {
    case LoadFoup                           => "LoadFoup"
    case Transport(from, to)                => s"Transport_${from}_${to}"
    case AtEquipment(area, equipId)         => s"AtEquipment_${area}_${equipId}"
    case TrackIn(equipId, _)                => s"TrackIn_${equipId}"
    case RunRecipe(equipId, recipeId)       => s"RunRecipe_${equipId}_${recipeId}"
    case TrackOut(equipId, _)               => s"TrackOut_${equipId}"
    case Measure(equipId)                   => s"Measure_${equipId}"
    case Classify                           => "Classify"
    case SagaSplit(lotKey)                  => s"SagaSplit_${lotKey}"
    case SagaMerge(lotKey)                  => s"SagaMerge_${lotKey}"
    case ScrapWafers                        => "ScrapWafers"
    case HoldWafers                         => "HoldWafers"
    case ReleaseWafers                      => "ReleaseWafers"
    case PostReleaseClassify                => "PostReleaseClassify"
    case WaitForReview(_)                   => "WaitForReview"
    case SealComplete                       => "SealComplete"
    case Branch(_, _, _)                    => "Branch"
    case PilotSubFlow                       => "PilotSubFlow"
    case OcapEvaluate(_)                    => "OcapEvaluate"
    case OcapActionRouter                   => "OcapActionRouter"
    case ExecuteSubProcess(ref)             => s"ExecuteSubProcess_${ref.subProcessType}"
    case AwaitSubLotResult(lotKey)          => s"AwaitSubLotResult_${lotKey}"
    case PhotoCellReworkPipeline            => "PhotoCellReworkPipeline"
    case DynamicPorExecution(_, _)          => "DynamicPorExecution"
    case _                                  => stage.getClass.getSimpleName
  }

  /** Unique cursor: repeated variants (Branch, Transport…) get their queue position. */
  private def cursorName(stage: PipelineStage, pos: Int): String =
    s"${stageName(stage)}#$pos"

  // ====================================================================
  // Execution
  // ====================================================================

  /** Execute the full queue starting from the given state. */
  def process(initialState: FabDemoState)(implicit ec: ExecutionContext): Future[FabDemoState] =
    executeQueue(entries, initialState)

  /**
   * Resume after recovery, skipping stages whose phase names appear in `completedPhases`.
   */
  def resume(state: FabDemoState, completedPhases: Set[String])(implicit ec: ExecutionContext): Future[FabDemoState] = {
    val remaining = entries.dropWhile { case (stage, pos) => completedPhases.contains(cursorName(stage, pos)) }
    executeQueue(remaining, state)
  }

  /** Resume using indexed skip where we know the exact count of completed phases. */
  def resumeFromIndex(state: FabDemoState, completedCount: Int)(implicit ec: ExecutionContext): Future[FabDemoState] =
    executeQueue(entries.drop(completedCount), state)

  // ====================================================================
  // Internal
  // ====================================================================

  private def executeQueue(
    remaining: Vector[(PipelineStage, Int)],
    state: FabDemoState
  )(implicit ec: ExecutionContext): Future[FabDemoState] = {
    remaining match {
      case v if v.isEmpty =>
        Future.successful(state)
      case (stage, pos) +: tail =>
        // P0: a superseded run terminates silently — no OCAP, no events, no side effects.
        if (!ctx.runToken()) Future.failed(StaleRun)
        else {
          val sn = cursorName(stage, pos)
          onPhaseStart(sn)
          runStage(stage, state, ctx).flatMap { nextState =>
            // P1: every stage carries its post-state so recovery state matches the cursor.
            onPhaseComplete(sn, Map.empty, Some(nextState))
            executeQueue(tail, nextState)
          }.recoverWith {
            case e if !ctx.runToken() => Future.failed(e)
            case StageFailedException(err) =>
              onPhaseFailed(sn, err)
              // P2: OCAP resolution is journaled as its own event, not a fake StageCompleted.
              FabScenarioPipeline.invokeOcapInterceptor(state, ctx, err).flatMap { ocapState =>
                onOcapResolved(sn, err, ocapState)
                executeQueue(tail, ocapState)
              }
            case NonFatal(ex) =>
              // P3: unexpected failures are journaled and routed through OCAP/manual handling —
              // never silently swallowed with a stale state.
              val err = StageError(sn, None, "UNEXPECTED", ex.getMessage)
              onPhaseFailed(sn, err)
              FabScenarioPipeline.invokeOcapInterceptor(state, ctx, err).flatMap { ocapState =>
                onOcapResolved(sn, err, ocapState)
                executeQueue(tail, ocapState)
              }
          }
        }
    }
  }

  private def runStage(stage: PipelineStage, state: FabDemoState, ctx: FabDemoContext): Future[FabDemoState] =
    FabScenarioPipeline.runStage(stage, state, ctx)
}

object FabPipelineProcessor {
  /** Factory method to create a processor with a pre-initialized stage list. */
  def apply(
    stages: Seq[PipelineStage],
    ctx: FabDemoContext,
    onPhaseStart: String => Unit,
    onPhaseComplete: (String, Map[String, String], Option[FabDemoState]) => Unit,
    onOcapResolved: (String, StageError, FabDemoState) => Unit,
    onPhaseFailed: (String, StageError) => Unit
  ): FabPipelineProcessor = {
    val p = new FabPipelineProcessor(ctx, onPhaseStart, onPhaseComplete, onOcapResolved, onPhaseFailed)
    p.initialize(stages)
    p
  }
}
