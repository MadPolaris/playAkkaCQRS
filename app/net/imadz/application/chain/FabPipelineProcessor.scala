package net.imadz.application.chain

import net.imadz.domain.events._
import net.imadz.application.chain.FabScenarioPipeline.{PipelineStage, _}
import net.imadz.application.chain.FabExecutionModel.{FabDemoContext, FabDemoState, StageFailedException}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/**
 * Queue-based pipeline processor that supports stage-level completion tracking,
 * runtime dynamic injection (injectHead/appendTail), and crash recovery.
 *
 * Designed to be wrapped by [[FabPipelineExecutionActor]] for EventSourced persistence.
 * Each completed stage triggers `onPhaseComplete` which signals the actor to persist
 * a PhaseDone event.
 *
 * Stage name derivation converts each [[PipelineStage]] variant to a stable string
 * cursor suitable for use as an event-sourcing phase cursor.
 */
class FabPipelineProcessor(
  ctx: FabDemoContext,
  onPhaseComplete: (String, Map[String, String], Option[FabDemoState]) => Unit
) {

  private var queue: Vector[PipelineStage] = Vector.empty

  /** Initialise the processor with the full stage list. */
  def initialize(stages: Seq[PipelineStage]): Unit = {
    queue = stages.toVector
  }

  /** Prepend stages to the head of the queue (Branch / OCAP runtime weaving). */
  def injectHead(stages: Seq[PipelineStage]): Unit = {
    queue = stages.toVector ++ queue
  }

  /** Append stages to the tail of the queue. */
  def appendTail(stages: Seq[PipelineStage]): Unit = {
    queue = queue ++ stages.toVector
  }

  /** Current queue size. */
  def pendingCount: Int = queue.size

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
    case _                                  => stage.getClass.getSimpleName
  }

  // ====================================================================
  // Execution
  // ====================================================================

  /**
   * Execute the full queue starting from the given state.
   * After each successful stage, `onPhaseComplete` is invoked.
   */
  def process(initialState: FabDemoState)(implicit ec: ExecutionContext): Future[FabDemoState] = {
    executeQueue(queue, initialState, ec)
  }

  /**
   * Resume execution after recovery, skipping stages whose phase names
   * appear in `completedPhases`. This enables crash recovery where only
   * uncompleted stages are re-executed.
   */
  def resume(state: FabDemoState, completedPhases: Set[String])(implicit ec: ExecutionContext): Future[FabDemoState] = {
    val remaining = queue.dropWhile(stage => completedPhases.contains(stageName(stage)))
    if (remaining.size == queue.size) {
      // Nothing was skipped — this is a fresh start or phases were interleaved
      executeQueue(queue, state, ec)
    } else {
      executeQueue(remaining, state, ec)
    }
  }

  /** Resume using indexed skip where we know the exact count of completed phases. */
  def resumeFromIndex(state: FabDemoState, completedCount: Int)(implicit ec: ExecutionContext): Future[FabDemoState] = {
    val remaining = queue.drop(completedCount)
    executeQueue(remaining, state, ec)
  }

  // ====================================================================
  // Internal
  // ====================================================================

  private def executeQueue(
    remaining: Vector[PipelineStage],
    state: FabDemoState,
    ec: ExecutionContext
  ): Future[FabDemoState] = {
    implicit val exec: ExecutionContext = ec
    remaining match {
      case v if v.isEmpty =>
        Future.successful(state)
      case stage +: tail =>
        runStage(stage, state, ctx).flatMap { nextState =>
          val sn = stageName(stage)
          val isHighValue = sn.startsWith("Measure") || sn == "Classify" || sn == "M35ClassifyWithOcap"
          val fabState = if (isHighValue) Some(nextState) else None
          onPhaseComplete(sn, Map.empty, fabState)
          executeQueue(tail, nextState, ec)
        }(ec).recoverWith {
          case StageFailedException(err) =>
            ctx.publisher(PipelineStageFailed(err.stageName, err.equipId, err.errorCode, err.detail))
            ctx.publisher(GlobalStatusChanged("FAILED", s"${err.stageName}: ${err.detail}", "PhaseFailed"))
            FabScenarioPipeline.invokeOcapInterceptor(state, ctx, err).flatMap { ocapState =>
              onPhaseComplete(stageName(stage), Map("ocap" -> err.stageName), None)
              executeQueue(tail, ocapState, ec)
            }(ec)
          case NonFatal(ex) =>
            ctx.publisher(GlobalStatusChanged("ERROR", s"Unexpected: ${ex.getMessage}", "PhaseFailed"))
            Future.successful(state)
        }(ec)
    }
  }
}

object FabPipelineProcessor {
  /** Factory method to create a processor with a pre-initialized stage list. */
  def apply(
    stages: Seq[PipelineStage],
    ctx: FabDemoContext,
    onPhaseComplete: (String, Map[String, String], Option[FabDemoState]) => Unit
  ): FabPipelineProcessor = {
    val p = new FabPipelineProcessor(ctx, onPhaseComplete)
    p.initialize(stages)
    p
  }
}
