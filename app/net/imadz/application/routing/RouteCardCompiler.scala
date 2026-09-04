package net.imadz.application.routing

import net.imadz.application.routing._
import net.imadz.domain.routing.RouteDefinition

/**
 * Compiles a RouteDefinition into a concrete RouteCard (Seq of PipelineStage identifiers).
 *
 * Used when:
 *   - A new Lot is created from a WorkOrder (InitialRoute)
 *   - A child lot is created via Saga Split (OCAP / Pilot / Sample / Hold)
 *   - A lot's route is updated mid-execution (OCAP triggers new route)
 */
object RouteCardCompiler {

  /**
   * Compile a full RouteDefinition into a flattened, ordered list of step identifiers.
   * Delegates to RouteCompiler for the graph-to-sequence translation.
   *
   * @param definition the route definition (from RoutingRepository)
   * @param startFrom  optional node ID to start compilation from (for branch-point entry)
   * @return ordered PipelineStage step identifiers
   */
  def compile(definition: RouteDefinition, startFrom: Option[String] = None): Seq[String] = {
    val stages = if (startFrom.isDefined) {
      // TODO: Phase 5 — compile from a specific branch point node
      // For now, compile the full route and filter from the start node
      RouteCompiler.compile(definition).map(stageIdentifier)
    } else {
      RouteCompiler.compile(definition).map(stageIdentifier)
    }
    stages
  }

  /**
   * Convert a PipelineStage to its canonical string identifier.
   * These identifiers match the format used in RouteCard.steps and
   * are consumed by FabScenarioPipeline.runStage for dispatch.
   */
  private def stageIdentifier(stage: net.imadz.application.chain.FabScenarioPipeline.PipelineStage): String = {
    import net.imadz.application.chain.FabScenarioPipeline._
    stage match {
      case LoadFoup                         => "LoadFoup"
      case Transport(from, to)              => s"Transport:$from->$to"
      case AtEquipment(area, equipId)       => s"AtEquipment:$area:$equipId"
      case TrackIn(equipId, portId)          => s"TrackIn:$equipId:$portId"
      case TrackOut(equipId, portId)         => s"TrackOut:$equipId:$portId"
      case RunRecipe(equipId, recipeId)     => s"RunRecipe:$equipId:$recipeId"
      case Measure(equipId)                 => s"Measure:$equipId"
      case Classify                         => "Classify"
      case SagaSplit(lotKey)                => s"SagaSplit:$lotKey"
      case SagaMerge(lotKey)                => s"SagaMerge:$lotKey"
      case ScrapWafers                      => "ScrapWafers"
      case HoldWafers                       => "HoldWafers"
      case ReleaseWafers                    => "ReleaseWafers"
      case PostReleaseClassify              => "PostReleaseClassify"
      case WaitForReview(durationMs)        => s"WaitForReview:$durationMs"
      case SealComplete                     => "SealComplete"
      case PilotSubFlow                     => "PilotSubFlow"
      case Branch(_, _, _)                  => "Branch"
      case OcapEvaluate(_)                  => "OcapEvaluate"
      case OcapActionRouter                 => "OcapActionRouter"
      case ExecuteSubProcess(ref)           => s"SubProcess:${ref.subProcessType}"
    }
  }
}
