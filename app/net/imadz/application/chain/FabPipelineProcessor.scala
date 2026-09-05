package net.imadz.application.chain

import net.imadz.application.chain.FabExecutionModel.{FabDemoContext, FabDemoState}
import net.imadz.application.chain.FabExecutionModel.{StageError => FabStageError, StageFailedException => FabStageFailedException}
import net.imadz.application.chain.FabScenarioPipeline.PipelineStage
import net.imadz.monarch.{Monarch, StageError => MonarchStageError, StageFailedException => MonarchStageFailedException}
import net.imadz.monarch.{FailureInterceptor, LifecycleHooks, StageInterpreter}

import scala.concurrent.{ExecutionContext, Future}

/**
 * Thin adapter between the Fab domain and [[net.imadz.monarch.Monarch]], the resumable
 * stage-queue engine (monarch-core). All mechanics — open stage queue, cursor naming,
 * resume-from-index, generation-token staleness, failure interception — live in the
 * engine now; this object supplies only Fab-specific knowledge:
 *
 *   - cursor naming from a [[PipelineStage]] variant (stable phase names, position-
 *     suffixed per queue entry)
 *   - the stage interpreter ([[FabScenarioPipeline.runStage]])
 *   - the failure interceptor ([[FabScenarioPipeline.invokeOcapInterceptor]])
 *   - the actor-journaled lifecycle callbacks (PhaseStarting/PhaseCompleted/
 *     PhaseFailed/OcapResolved protocols unchanged — journal and UI untouched)
 */
object FabPipelineProcessor {

  /** Derive a stable, human-readable phase name from a PipelineStage variant. */
  def stageName(stage: PipelineStage): String = stage match {
    case FabScenarioPipeline.LoadFoup                     => "LoadFoup"
    case FabScenarioPipeline.Transport(from, to)          => s"Transport_${from}_${to}"
    case FabScenarioPipeline.AtEquipment(area, equipId)   => s"AtEquipment_${area}_${equipId}"
    case FabScenarioPipeline.TrackIn(equipId, _)          => s"TrackIn_${equipId}"
    case FabScenarioPipeline.RunRecipe(equipId, recipeId) => s"RunRecipe_${equipId}_${recipeId}"
    case FabScenarioPipeline.TrackOut(equipId, _)         => s"TrackOut_${equipId}"
    case FabScenarioPipeline.Measure(equipId)             => s"Measure_${equipId}"
    case FabScenarioPipeline.Classify                     => "Classify"
    case FabScenarioPipeline.SagaSplit(lotKey)            => s"SagaSplit_${lotKey}"
    case FabScenarioPipeline.SagaMerge(lotKey)            => s"SagaMerge_${lotKey}"
    case FabScenarioPipeline.ScrapWafers                  => "ScrapWafers"
    case FabScenarioPipeline.HoldWafers                   => "HoldWafers"
    case FabScenarioPipeline.ReleaseWafers                => "ReleaseWafers"
    case FabScenarioPipeline.PostReleaseClassify          => "PostReleaseClassify"
    case FabScenarioPipeline.WaitForReview(_)             => "WaitForReview"
    case FabScenarioPipeline.SealComplete                 => "SealComplete"
    case FabScenarioPipeline.Branch(_, _, _)              => "Branch"
    case FabScenarioPipeline.PilotSubFlow                 => "PilotSubFlow"
    case FabScenarioPipeline.OcapEvaluate(_)              => "OcapEvaluate"
    case FabScenarioPipeline.OcapActionRouter             => "OcapActionRouter"
    case FabScenarioPipeline.ExecuteSubProcess(ref)       => s"ExecuteSubProcess_${ref.subProcessType}"
    case FabScenarioPipeline.AwaitSubLotResult(lotKey)    => s"AwaitSubLotResult_${lotKey}"
    case FabScenarioPipeline.PhotoCellReworkPipeline      => "PhotoCellReworkPipeline"
    case FabScenarioPipeline.DynamicPorExecution(_, _)    => "DynamicPorExecution"
    case _                                                => stage.getClass.getSimpleName
  }

  /** Build a Monarch engine wired to the Fab domain. The caller `initialize`s it with a
    * stage list, then drives `process` (fresh run) or `resumeFromIndex` (crash recovery). */
  def monarch(
      ctx: FabDemoContext,
      onPhaseStart: String => Unit,
      onPhaseComplete: (String, FabDemoState) => Unit,
      onOcapResolved: (String, FabStageError, FabDemoState) => Unit,
      onPhaseFailed: (String, FabStageError) => Unit
  )(implicit ec: ExecutionContext): Monarch[PipelineStage, FabDemoState] = {

    def toFabError(error: MonarchStageError): FabStageError =
      FabStageError(error.stage, error.code, error.errorCode, error.detail)

    new Monarch[PipelineStage, FabDemoState](
      interpreter = new StageInterpreter[PipelineStage, FabDemoState] {
        override def run(stage: PipelineStage, state: FabDemoState)(implicit ec: ExecutionContext): Future[FabDemoState] =
          FabScenarioPipeline.runStage(stage, state, ctx).recoverWith {
            // Preserve business classification: stage bodies throw the FAB exception type;
            // re-throw it as the engine's type so the failure is intercepted, not wrapped
            // as UNEXPECTED.
            case FabStageFailedException(err) =>
              Future.failed(MonarchStageFailedException(
                MonarchStageError(err.stageName, err.equipId, err.errorCode, err.detail)))
          }
      },
      hooks = new LifecycleHooks[PipelineStage, FabDemoState] {
        // NOTE: must qualify — an unqualified stageName(stage) here resolves to this
        // anonymous override itself and tail-recurses into a silent infinite loop.
        override def stageName(stage: PipelineStage): String = FabPipelineProcessor.stageName(stage)
        override def onStageStart(cursor: String): Unit = onPhaseStart(cursor)
        override def onStageComplete(cursor: String, state: FabDemoState, metadata: Map[String, String]): Unit =
          onPhaseComplete(cursor, state)
        override def onStageFailed(cursor: String, error: MonarchStageError): Unit =
          onPhaseFailed(cursor, toFabError(error))
        override def onStageResolved(cursor: String, error: MonarchStageError, state: FabDemoState): Unit =
          onOcapResolved(cursor, toFabError(error), state)
      },
      failureInterceptor = Some(new FailureInterceptor[PipelineStage, FabDemoState] {
        override def intercept(cursor: String, error: MonarchStageError, state: FabDemoState)(implicit ec: ExecutionContext): Future[FabDemoState] =
          FabScenarioPipeline.invokeOcapInterceptor(state, ctx, toFabError(error))
      }),
      runToken = ctx.runToken
    )
  }
}
