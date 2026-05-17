package net.imadz.fab.routing

import net.imadz.fab.model.FabExecutionModel.FabDemoState

/**
 * Shared condition expression evaluator used by both RouteCompiler
 * (for Branch node conditions) and OcapEngine (for OCAP rule triggers).
 *
 * Pure function — no side effects, no dependencies on external state.
 */
object ConditionEvaluator {

  def evaluate(cond: ConditionExpression, state: FabDemoState): Boolean = cond match {
    case MeasurementCondition(metric, op, lower, upper, scope) =>
      val values: Iterable[Double] = metric match {
        case "cd_nm" => state.wafers.values.flatMap(_.cdValueHistory.lastOption)
        case _       => state.wafers.values.flatMap(_.cdValueHistory.lastOption)
      }
      if (values.isEmpty) false
      else scope match {
        case AllWafers            => values.forall(v => compareOp(v, op, lower, upper))
        case AnyWafer             => values.exists(v => compareOp(v, op, lower, upper))
        case SlotRange(from, to)  =>
          values.zipWithIndex.filter { case (_, i) => i >= from && i <= to }
            .forall { case (v, _) => compareOp(v, op, lower, upper) }
      }

    case AggregateCondition(conditions, logic) =>
      logic match {
        case And => conditions.forall(c => evaluate(c, state))
        case Or  => conditions.exists(c => evaluate(c, state))
        case Not => !conditions.forall(c => evaluate(c, state))
      }
  }

  def evaluateValue(value: Double, op: ComparisonOp, lower: Double, upper: Double): Boolean = op match {
    case GreaterThan  => value > lower
    case LessThan     => value < lower
    case WithinRange  => value >= lower && value <= upper
    case OutsideRange => value < lower || value > upper
  }

  private def compareOp(value: Double, op: ComparisonOp, lower: Double, upper: Double): Boolean =
    evaluateValue(value, op, lower, upper)
}
