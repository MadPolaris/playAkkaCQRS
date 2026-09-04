package net.imadz.application.routing

import net.imadz.domain.routing._

import net.imadz.domain.routing._

import net.imadz.application.chain.FabExecutionModel.FabDemoState
import org.slf4j.LoggerFactory

/**
 * Shared condition expression evaluator used by both RouteCompiler
 * (for Branch node conditions) and OcapEngine (for OCAP rule triggers).
 *
 * Pure function — no side effects, no dependencies on external state.
 */
object ConditionEvaluator {

  private val log = LoggerFactory.getLogger(ConditionEvaluator.getClass)

  private val missingMetricLogCache =
    java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap[String, java.lang.Boolean]())

  /**
   * Evaluate a condition expression against the current Fab state.
   *
   * @param cond     the condition to evaluate
   * @param state    the current [[FabDemoState]]
   * @param onMissing policy when a metric name is not found in [[MetricResolver]]
   *                 (default [[ConservativePass]] = skip the rule)
   * @return true if the condition is satisfied
   */
  def evaluate(cond: ConditionExpression, state: FabDemoState,
               onMissing: MissingMetricPolicy = ConservativePass): Boolean = cond match {

    case MeasurementCondition(metric, op, lower, upper, scope) =>
      MetricResolver.resolve(metric, state) match {
        case Some(values) =>
          scope match {
            case AllWafers            => values.forall(v => compareOp(v, op, lower, upper))
            case AnyWafer             => values.exists(v => compareOp(v, op, lower, upper))
            case SlotRange(from, to)  =>
              values.zipWithIndex.filter { case (_, i) => i >= from && i <= to }
                .forall { case (v, _) => compareOp(v, op, lower, upper) }
          }
        case None =>
          // Log once per metric to avoid log spam
          if (missingMetricLogCache.add(metric)) {
            log.warn(s"Metric '$metric' not found in MetricResolver; onMissing=$onMissing")
          }
          onMissing match {
            case ConservativeReject => true   // trigger rule
            case ConservativePass   => false  // skip rule
          }
      }

    case EquipmentCondition(equipmentId, metric, op, lower, upper) =>
      state.equipmentState.get(equipmentId) match {
        case Some(eqState) =>
          val value: Double = metric match {
            case "errorCount" => eqState.errorCount.toDouble
            case _            => 0.0
          }
          compareOp(value, op, lower, upper)
        case None =>
          if (missingMetricLogCache.add(s"equipment:$equipmentId")) {
            log.warn(s"Equipment '$equipmentId' not found in state.equipmentState")
          }
          onMissing match {
            case ConservativeReject => true
            case ConservativePass   => false
          }
      }

    case AggregateCondition(conditions, logic) =>
      logic match {
        case And => conditions.forall(c => evaluate(c, state, onMissing))
        case Or  => conditions.exists(c => evaluate(c, state, onMissing))
        case Not => !conditions.forall(c => evaluate(c, state, onMissing))
      }
  }

  def evaluateValue(value: Double, op: ComparisonOp, lower: Double, upper: Double): Boolean = op match {
    case GreaterThan        => value > lower
    case GreaterThanOrEqual => value >= lower
    case LessThan           => value < lower
    case LessThanOrEqual    => value <= lower
    case WithinRange        => value >= lower && value <= upper
    case OutsideRange       => value < lower || value > upper
  }

  private def compareOp(value: Double, op: ComparisonOp, lower: Double, upper: Double): Boolean =
    evaluateValue(value, op, lower, upper)
}
