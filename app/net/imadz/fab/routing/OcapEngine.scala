package net.imadz.fab.routing

import net.imadz.fab.events._
import net.imadz.fab.model.FabExecutionModel.{FabDemoContext, FabDemoState}

import scala.concurrent.{ExecutionContext, Future}

/**
 * OCAP (Out-of-Control Action Plan) rule engine.
 *
 * Evaluates OCAP rules against the current FabDemoState, matches triggered rules,
 * publishes OcapActionTriggered events for UI visibility, and applies immediate
 * state changes for non-pipeline actions (Notify, AdjustRecipe).
 *
 * Pipeline actions (Hold, Rework, Scrap) are only published — the pipeline's
 * subsequent stages (HoldWafers, SagaSplit, ScrapWafers) execute the actual work.
 *
 * Design: pure condition evaluation (via [[ConditionEvaluator]]), rule matching
 * sorted by priority, action execution that may mutate state for informational actions.
 */
object OcapEngine {

  /**
   * Main entry point: evaluate OCAP rules against current state.
   *
   * @param state current Fab simulation state
   * @param ctx   pipeline context (for event publishing)
   * @param rules OCAP rules to evaluate
   * @return updated state (may have wafer annotations for downstream stages)
   */
  def evaluate(state: FabDemoState, ctx: FabDemoContext, rules: List[OcapRuleDefinition])(
    implicit ec: ExecutionContext
  ): Future[FabDemoState] = {
    val triggered = matchRules(state, rules)
    if (triggered.isEmpty) {
      Future.successful(state)
    } else {
      // Publish triggered rules for UI visibility
      triggered.foreach { rule =>
        ctx.publisher(OcapActionTriggered(
          ruleId = rule.ruleId,
          ruleName = rule.name,
          actionType = rule.actionPlan match {
            case _: OcapHold         => "HOLD"
            case _: OcapRework       => "REWORK"
            case _: OcapScrap        => "SCRAP"
            case _: OcapNotify       => "NOTIFY"
            case _: OcapAdjustRecipe => "ADJUST_RECIPE"
            case _: OcapComposite    => "COMPOSITE"
          },
          detail = describeAction(rule.actionPlan),
          affectedWafers = findAffectedWafers(state, rule.triggerCondition)
        ))
      }

      // Apply immediate state changes for non-pipeline actions
      val updated = triggered.foldLeft(state) { (s, rule) =>
        applyImmediateAction(s, rule)
      }

      Future.successful(updated)
    }
  }

  /**
   * Match OCAP rules against current state, returning triggered rules
   * sorted by priority (lower value = higher priority).
   */
  def matchRules(state: FabDemoState, rules: List[OcapRuleDefinition]): List[OcapRuleDefinition] =
    rules
      .filter(r => ConditionEvaluator.evaluate(r.triggerCondition, state))
      .sortBy(_.priority)

  /**
   * Evaluate a single condition expression against state.
   * Delegates to [[ConditionEvaluator]].
   */
  def evaluateCondition(cond: ConditionExpression, state: FabDemoState): Boolean =
    ConditionEvaluator.evaluate(cond, state)

  // ---- Private helpers ----

  private def describeAction(plan: OcapActionPlan): String = plan match {
    case OcapHold(durationMs, reason)             => s"HOLD (${durationMs}ms): $reason"
    case OcapRework(recipeId, maxCount)           => s"REWORK: recipe=$recipeId, max=$maxCount"
    case OcapScrap(reason)                        => s"SCRAP: $reason"
    case OcapNotify(reason, escalationPath)       => s"NOTIFY: $reason → $escalationPath"
    case OcapAdjustRecipe(recipeId, offsetNm)     => s"ADJUST: recipe=$recipeId, offset=${offsetNm}nm"
    case OcapComposite(actions)                   => s"COMPOSITE(${actions.map(describeAction).mkString("; ")})"
  }

  private def findAffectedWafers(state: FabDemoState, cond: ConditionExpression): Seq[String] = {
    cond match {
      case MeasurementCondition("cd_nm", op, lower, upper, _) =>
        state.wafers.collect {
          case (wid, info) if info.cdValueHistory.lastOption.exists(v =>
            ConditionEvaluator.evaluateValue(v, op, lower, upper)) => wid
        }.toSeq
      case _ => Seq.empty
    }
  }

  private def applyImmediateAction(state: FabDemoState, rule: OcapRuleDefinition): FabDemoState = {
    rule.actionPlan match {
      case OcapAdjustRecipe(_, _) =>
        // Mark state that recipe was adjusted (informational)
        state
      case OcapComposite(actions) =>
        actions.foldLeft(state) { (s, a) =>
          applyImmediateAction(s, rule.copy(actionPlan = a))
        }
      case _ => state // Hold/Rework/Scrap/Notify — pipeline handles these
    }
  }
}
