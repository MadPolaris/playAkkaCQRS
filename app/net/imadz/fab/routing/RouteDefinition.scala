package net.imadz.fab.routing

/**
 * Configurable Fab route system — data model.
 *
 * A RouteDefinition is the primary artifact that process engineers configure
 * (via visual editor, DSL, or JSON/YAML). It gets compiled to the existing
 * PipelineStage ADT for execution.
 *
 * Design invariants:
 *   - SubProcessRef uses LATE-BINDING: resolved at runtime, not compile time.
 *   - OCAP rules are stored independently from route nodes for separate lifecycle.
 *   - ConditionExpression is pure data — evaluated by OcapEngine/classify at runtime.
 */

// ============================================================================
// Route Definition
// ============================================================================

case class RouteDefinition(
  routeId: String,
  productId: String,
  version: Int = 1,
  name: String,
  description: String = "",
  nodes: List[RouteNode],
  edges: List[RouteEdge],
  ocapRules: List[OcapRuleDefinition] = Nil,
  metadata: RouteMetadata = RouteMetadata()
)

case class RouteMetadata(
  createdBy: String = "system",
  createdAt: Long = System.currentTimeMillis(),
  tags: List[String] = Nil,
  notes: String = ""
)

// ============================================================================
// Route Nodes
// ============================================================================

sealed trait RouteNode {
  def nodeId: String
  def label: String
}

/** An atomic fab operation — maps directly to a PipelineStage. */
case class AtomicStep(
  nodeId: String,
  label: String,
  operationType: AtomicOperationType,
  config: Map[String, String] = Map.empty
) extends RouteNode

/** Reference to a reusable sub-process — RESOLVED AT RUNTIME (Call Activity pattern).
 *  Not statically expanded at compile time, so in-flight lots always use the
 *  latest version of the sub-process when they reach this node. */
case class SubProcessRef(
  nodeId: String,
  label: String,
  subProcessType: SubProcessType,
  params: Map[String, String] = Map.empty
) extends RouteNode

/** Conditional branch — evaluates a condition expression against current state. */
case class DecisionNode(
  nodeId: String,
  label: String,
  condition: ConditionExpression
) extends RouteNode

/** Saga TCC operation — split wafers to child lot, or merge back. */
case class SagaStep(
  nodeId: String,
  label: String,
  sagaType: SagaType,
  lotKey: String,
  waferSelection: WaferSelection
) extends RouteNode

/** OCAP action — contains rules that trigger when conditions are met. */
case class OcapNode(
  nodeId: String,
  label: String,
  ocapRules: List[OcapRuleDefinition]
) extends RouteNode

/** Wait/delay node — for simulated engineering review etc. */
case class WaitNode(
  nodeId: String,
  label: String,
  durationMs: Long
) extends RouteNode

// ============================================================================
// Node Type Enums
// ============================================================================

sealed trait AtomicOperationType
case object LoadFoupOp       extends AtomicOperationType
case object TransportOp      extends AtomicOperationType
case object AtEquipmentOp    extends AtomicOperationType
case object RunRecipeOp      extends AtomicOperationType
case object MeasureOp        extends AtomicOperationType
case object ClassifyOp       extends AtomicOperationType
case object SealCompleteOp   extends AtomicOperationType
case object HoldWafersOp     extends AtomicOperationType
case object ReleaseWafersOp  extends AtomicOperationType

sealed trait SubProcessType
case object SendAheadPilot extends SubProcessType
case object ReworkLoop     extends SubProcessType
case object HoldRelease    extends SubProcessType
case object Sampling       extends SubProcessType
case object ScrapDowngrade extends SubProcessType

sealed trait SagaType
case object SagaSplitOp extends SagaType
case object SagaMergeOp extends SagaType

sealed trait WaferSelection
case class FixedCount(count: Int)                        extends WaferSelection
case class ByClassification(classification: String)       extends WaferSelection
case class BySlot(slotIndices: List[Int])                 extends WaferSelection

// ============================================================================
// Route Edges
// ============================================================================

case class RouteEdge(
  edgeId: String,
  sourceNodeId: String,
  targetNodeId: String,
  edgeType: EdgeType = MaterialFlow,
  condition: Option[ConditionExpression] = None,
  label: String = ""
)

sealed trait EdgeType
case object MaterialFlow   extends EdgeType  // normal wafer flow
case object ExceptionFlow  extends EdgeType  // fallback / error flow
case object OcapFlow       extends EdgeType  // OCAP trigger flow
case object OtherwiseFlow  extends EdgeType  // default fallback

// ============================================================================
// Condition Expressions (evaluated at runtime by OcapEngine / classify)
// ============================================================================

sealed trait ConditionExpression

case class MeasurementCondition(
  metric: String,          // "cd_nm", "thickness_nm", etc.
  operator: ComparisonOp,
  lowerBound: Double = 0.0,
  upperBound: Double = Double.MaxValue,
  waferScope: WaferScope = AllWafers
) extends ConditionExpression

case class AggregateCondition(
  conditions: List[ConditionExpression],
  logic: LogicOp
) extends ConditionExpression

sealed trait ComparisonOp
case object GreaterThan   extends ComparisonOp
case object LessThan      extends ComparisonOp
case object WithinRange   extends ComparisonOp
case object OutsideRange  extends ComparisonOp

sealed trait LogicOp
case object And extends LogicOp
case object Or  extends LogicOp
case object Not extends LogicOp

sealed trait WaferScope
case object AllWafers            extends WaferScope
case object AnyWafer             extends WaferScope
case class SlotRange(from: Int, to: Int) extends WaferScope

// ============================================================================
// OCAP Rule Definition (stored independently from route for versioned lifecycle)
// ============================================================================

case class OcapRuleDefinition(
  ruleId: String,
  name: String,
  triggerCondition: ConditionExpression,
  actionPlan: OcapActionPlan,
  priority: Int = 0,             // lower = higher priority
  maxTriggersPerLot: Int = 3
)

sealed trait OcapActionPlan
case class OcapHold(durationMs: Long, reason: String)        extends OcapActionPlan
case class OcapRework(recipeId: String, maxCount: Int)        extends OcapActionPlan
case class OcapScrap(reason: String)                          extends OcapActionPlan
case class OcapNotify(reason: String, escalationPath: String) extends OcapActionPlan
case class OcapAdjustRecipe(recipeId: String, offsetNm: Double) extends OcapActionPlan
case class OcapComposite(actions: List[OcapActionPlan])       extends OcapActionPlan
