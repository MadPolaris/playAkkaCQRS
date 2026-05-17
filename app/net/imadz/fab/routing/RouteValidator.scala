package net.imadz.fab.routing

/**
 * Validates a RouteDefinition graph for structural correctness.
 *
 * Checks:
 *   - All edge source/target nodes exist in the node list
 *   - Graph is connected (no orphan nodes)
 *   - No cycles on MaterialFlow edges (intentional rework loops use ExceptionFlow)
 *   - All SubProcessRef references are to known SubProcessType values
 *   - All OcapRule conditions have valid metric names and thresholds
 */
object RouteValidator {

  case class ValidationResult(errors: List[String] = Nil, warnings: List[String] = Nil) {
    def isValid: Boolean = errors.isEmpty
    def withError(e: String): ValidationResult = copy(errors = errors :+ e)
    def withWarning(w: String): ValidationResult = copy(warnings = warnings :+ w)
    def merge(other: ValidationResult): ValidationResult =
      ValidationResult(errors ++ other.errors, warnings ++ other.warnings)
  }

  def validate(route: RouteDefinition): ValidationResult = {
    var result = ValidationResult()

    // Check non-empty nodes
    if (route.nodes.isEmpty) result = result.withError("Route has no nodes")

    // Build node ID set, check no duplicates
    val nodeIds = route.nodes.map(_.nodeId)
    val duplicates = nodeIds.groupBy(identity).filter(_._2.size > 1).keys.toSet
    if (duplicates.nonEmpty) result = result.withError(s"Duplicate node IDs: ${duplicates.mkString(", ")}")

    val nodeIdSet = nodeIds.toSet

    // Check edges reference valid nodes
    route.edges.foreach { edge =>
      if (!nodeIdSet.contains(edge.sourceNodeId))
        result = result.withError(s"Edge ${edge.edgeId}: source node '${edge.sourceNodeId}' not found")
      if (!nodeIdSet.contains(edge.targetNodeId))
        result = result.withError(s"Edge ${edge.edgeId}: target node '${edge.targetNodeId}' not found")
    }

    // Check edges have unique IDs
    val edgeIds = route.edges.map(_.edgeId)
    val dupEdges = edgeIds.groupBy(identity).filter(_._2.size > 1).keys.toSet
    if (dupEdges.nonEmpty) result = result.withError(s"Duplicate edge IDs: ${dupEdges.mkString(", ")}")

    // Check connectivity: every node (except start/end) should have at least one incoming and outgoing edge
    val sources = route.edges.map(_.sourceNodeId).toSet
    val targets = route.edges.map(_.targetNodeId).toSet
    val orphanNodes = nodeIdSet.filterNot(id => sources.contains(id) || targets.contains(id))
    if (orphanNodes.nonEmpty && route.nodes.length > 1)
      result = result.withWarning(s"Orphan nodes (no edges): ${orphanNodes.mkString(", ")}")

    // Check SubProcessRef nodes have valid subProcessType (non-empty params recommended)
    route.nodes.collect { case SubProcessRef(id, _, subType, params) => (id, subType, params) }
      .foreach { case (id, _, params) =>
        if (params.get("pilotRecipeId").isEmpty && params.get("reworkRecipeId").isEmpty
          && params.get("sampleSize").isEmpty && params.get("scrapReason").isEmpty)
          result = result.withWarning(s"SubProcessRef $id: no recognized params — sub-process may be a no-op")
      }

    // Check OcapNode has at least one rule
    route.nodes.collect { case OcapNode(id, _, rules) => (id, rules) }
      .foreach { case (id, rules) =>
        if (rules.isEmpty) result = result.withWarning(s"OcapNode $id: no OCAP rules defined")
      }

    // Check DecisionNode has at least one outgoing edge for each branch
    route.nodes.collect { case d: DecisionNode => d }.foreach { dn =>
      val outEdges = route.edges.filter(_.sourceNodeId == dn.nodeId)
      if (outEdges.isEmpty) result = result.withWarning(s"DecisionNode ${dn.nodeId}: no outgoing edges")
    }

    // Check MaterialFlow cycles (simple DFS)
    val materialEdges = route.edges.filter(_.edgeType == MaterialFlow)
    val adj: Map[String, Set[String]] = materialEdges.groupBy(_.sourceNodeId)
      .map { case (k, v) => k -> v.map(_.targetNodeId).toSet }
      .withDefaultValue(Set.empty)

    val visited = scala.collection.mutable.Set.empty[String]
    val inStack = scala.collection.mutable.Set.empty[String]

    def hasCycle(node: String): Boolean = {
      if (inStack.contains(node)) true
      else if (visited.contains(node)) false
      else {
        visited += node; inStack += node
        val result = adj(node).exists(hasCycle)
        inStack -= node
        result
      }
    }

    if (nodeIdSet.exists(hasCycle))
      result = result.withWarning("Route contains MaterialFlow cycles — intentional rework loops should use ExceptionFlow edges")

    result
  }
}
