package net.imadz.fab.routing

import net.imadz.fab.chain.FabScenarioPipeline._
import net.imadz.fab.model.FabExecutionModel.FabDemoState

/**
 * Compiles a RouteDefinition into a Seq[PipelineStage] for execution.
 *
 * Walks the graph from the first node along MaterialFlow edges,
 * translating each RouteNode into the appropriate PipelineStage.
 *
 * DecisionNodes produce Branch stages that recursively compile their
 * true/false sub-sequences. ExceptionFlow edges on decisions become
 * the false branch; OcapFlow edges become OcapEvaluate stages.
 */
object RouteCompiler {

  /**
   * Compile a validated RouteDefinition.
   * @param route the route to compile
   * @return a sequence of PipelineStage ready for execution
   */
  def compile(route: RouteDefinition): Seq[PipelineStage] = {
    if (route.nodes.isEmpty) return Seq.empty

    val nodeMap: Map[String, RouteNode] = route.nodes.map(n => n.nodeId -> n).toMap
    val edgesBySource: Map[String, List[RouteEdge]] = route.edges.groupBy(_.sourceNodeId).withDefaultValue(Nil)

    // Find the start node (node with no incoming MaterialFlow edges)
    val materialTargets = route.edges.filter(_.edgeType == MaterialFlow).map(_.targetNodeId).toSet
    val startNode = route.nodes.find(n => !materialTargets.contains(n.nodeId))
      .getOrElse(route.nodes.head)

    compileFrom(startNode.nodeId, nodeMap, edgesBySource, route)
  }

  private def compileFrom(
    nodeId: String,
    nodeMap: Map[String, RouteNode],
    edgesBySource: Map[String, List[RouteEdge]],
    route: RouteDefinition
  ): Seq[PipelineStage] = {
    nodeMap.get(nodeId).map { node =>
      val outgoing = edgesBySource(nodeId)

      val ownStage: PipelineStage = node match {
        case AtomicStep(_, _, opType, config) =>
          translateAtomic(opType, config)

        case SubProcessRef(id, label, subType, params) =>
          ExecuteSubProcess(SubProcessRef(id, label, subType, params))

        case SagaStep(_, _, sagaType, lotKey, _) =>
          sagaType match {
            case SagaSplitOp => SagaSplit(lotKey)
            case SagaMergeOp => SagaMerge(lotKey)
          }

        case d: DecisionNode =>
          val materialTargets = outgoing.filter(_.edgeType == MaterialFlow)
          val exceptionTargets = outgoing.filter(e => e.edgeType == ExceptionFlow || e.edgeType == OtherwiseFlow)
          val ocapTargets = outgoing.filter(_.edgeType == OcapFlow)

          val trueStages: Seq[PipelineStage] = materialTargets.headOption
            .map(e => compileFrom(e.targetNodeId, nodeMap, edgesBySource, route)).getOrElse(Seq.empty)

          val falseStages: Seq[PipelineStage] = exceptionTargets.headOption
            .map(e => compileFrom(e.targetNodeId, nodeMap, edgesBySource, route))
            .orElse(materialTargets.drop(1).headOption.map(e => compileFrom(e.targetNodeId, nodeMap, edgesBySource, route)))
            .getOrElse(Seq.empty)

          val ocapStages: Seq[PipelineStage] = ocapTargets.flatMap { edge =>
            nodeMap.get(edge.targetNodeId) match {
              case Some(OcapNode(_, _, rules)) => Seq(OcapEvaluate(rules))
              case _ => Seq.empty
            }
          }

          Branch(
            cond = (state: FabDemoState) => evaluateCondition(d.condition, state),
            ifTrue = trueStages,
            ifFalse = falseStages ++ ocapStages
          )

        case OcapNode(_, _, rules) =>
          OcapEvaluate(rules)

        case WaitNode(_, _, durationMs) =>
          WaitForReview(durationMs)
      }

      // Collect OCAP stages from OcapFlow edges attached to this node
      val ocapStages: Seq[PipelineStage] = outgoing.filter(_.edgeType == OcapFlow).flatMap { edge =>
        nodeMap.get(edge.targetNodeId) match {
          case Some(OcapNode(_, _, rules)) => Seq(OcapEvaluate(rules))
          case _ => Seq.empty
        }
      }

      // Follow MaterialFlow to the next node (DecisionNode is terminal in the sequence)
      val nextMaterialEdge = outgoing.find(_.edgeType == MaterialFlow)
      val tail = nextMaterialEdge match {
        case Some(edge) if !node.isInstanceOf[DecisionNode] =>
          compileFrom(edge.targetNodeId, nodeMap, edgesBySource, route)
        case _ =>
          Seq.empty
      }

      ownStage +: ocapStages ++: tail
    }.getOrElse(Seq.empty)
  }

  private def translateAtomic(opType: AtomicOperationType, config: Map[String, String]): PipelineStage = {
    opType match {
      case LoadFoupOp       => LoadFoup
      case TransportOp      => Transport(config.getOrElse("from", "STOCKER"), config.getOrElse("to", "LITHO"))
      case AtEquipmentOp    => AtEquipment(config.getOrElse("area", "LITHO"), config.getOrElse("equipId", "LITHO-01"))
      case RunRecipeOp      => RunRecipe(config.getOrElse("equipId", "LITHO-01"), config.getOrElse("recipeId", "LITHO-28-001"))
      case MeasureOp        => Measure(config.getOrElse("equipId", "CDSEM-01"))
      case ClassifyOp       => Classify
      case SealCompleteOp   => SealComplete
      case HoldWafersOp     => HoldWafers
      case ReleaseWafersOp  => ReleaseWafers
    }
  }

  /** Evaluate a condition expression against the current Fab state.
   *  Delegates to [[ConditionEvaluator]]. */
  private def evaluateCondition(cond: ConditionExpression, state: FabDemoState): Boolean =
    ConditionEvaluator.evaluate(cond, state)
}
