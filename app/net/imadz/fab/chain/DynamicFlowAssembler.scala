package net.imadz.fab.chain

import net.imadz.fab.model.{EquipmentArea, RoutingStep}
import net.imadz.fab.scenario.DecisionConfig

/**
 * Runtime decision engine for Fab process routing.
 *
 * Pure functions — zero dependencies on actors, futures, or side effects.
 *
 * Four-way wafer disposition:
 *   PASS → Continue to next process step
 *   FAIL → Saga Split + Rework (split FAIL wafers to rework lot, return to Litho)
 *   BORDERLINE → Conditional pass (first time) or split rework (subsequent)
 *   SCRAP → Terminate wafer
 *
 * Aggregates per-wafer dispositions into a step-level decision for the flow engine.
 */
sealed trait WaferDisposition
case class PassDisposition(waferId: String) extends WaferDisposition
case class ReworkDisposition(waferId: String, attempt: Int, maxRetries: Int) extends WaferDisposition
case class ScrapDisposition(waferId: String, reason: String) extends WaferDisposition
case class HoldDisposition(waferId: String, reason: String) extends WaferDisposition

sealed trait StepDecision
case object AdvanceToNextStep extends StepDecision
case class RetryCurrentStep(waferIds: Set[String], reason: String) extends StepDecision
case class SplitAndRework(waferIds: Set[String], reason: String) extends StepDecision
case class HoldAndReview(waferIds: Set[String], reason: String) extends StepDecision
case class ScrapWafersDecision(waferIds: Set[String], reason: String) extends StepDecision
case class FallbackToArea(area: EquipmentArea, reason: String) extends StepDecision

object DynamicFlowAssembler {

  /**
   * Classify a single wafer measurement into a disposition.
   *
   * @param cdValue     measured critical dimension in nm
   * @param spec        process specification limits
   * @param reworkCount how many times this wafer has already been reworked
   */
  def classifyWafer(cdValue: Double, spec: DecisionConfig, reworkCount: Int): WaferDisposition = {
    if (cdValue >= spec.lowerSpecNm && cdValue <= spec.upperSpecNm) {
      // Within spec → PASS
      PassDisposition(s"wafer-$cdValue")
    } else if (cdValue > spec.upperSpecNm && cdValue <= spec.upperSpecNm + spec.borderlineWindowNm) {
      // Borderline region
      if (reworkCount == 0)
        PassDisposition(s"wafer-$cdValue") // first borderline → conditional pass
      else if (reworkCount >= spec.maxReworkCount)
        ScrapDisposition(s"wafer-$cdValue", s"Borderline CD=$cdValue after $reworkCount reworks — max exceeded")
      else
        ReworkDisposition(s"wafer-$cdValue", reworkCount + 1, spec.maxReworkCount)
    } else if (cdValue > spec.upperSpecNm + 8.0) {
      // Far out of spec → SCRAP directly
      ScrapDisposition(s"wafer-$cdValue", s"CD=$cdValue nm far out of spec (limit=${spec.upperSpecNm + 8.0})")
    } else {
      // Below lower spec or in FAIL zone → REWORK
      if (reworkCount >= spec.maxReworkCount)
        ScrapDisposition(s"wafer-$cdValue", s"CD=$cdValue after $reworkCount reworks — max exceeded")
      else
        ReworkDisposition(s"wafer-$cdValue", reworkCount + 1, spec.maxReworkCount)
    }
  }

  /**
   * Aggregate per-wafer dispositions into a step-level decision.
   *
   * Priority: SCRAP > HOLD > REWORK > ADVANCE
   */
  def decideNextStep(dispositions: Map[String, WaferDisposition], step: RoutingStep): StepDecision = {
    if (dispositions.isEmpty) return AdvanceToNextStep

    val scraps = dispositions.collect { case (wid, d: ScrapDisposition) => wid -> d }
    val holds = dispositions.collect { case (wid, d: HoldDisposition) => wid -> d }
    val reworks = dispositions.collect { case (wid, d: ReworkDisposition) => wid -> d }

    if (scraps.nonEmpty) {
      ScrapWafersDecision(scraps.keySet, scraps.values.map(_.reason).mkString("; "))
    } else if (holds.nonEmpty) {
      HoldAndReview(holds.keySet, holds.values.map(_.reason).mkString("; "))
    } else if (reworks.nonEmpty) {
      // FAIL wafers must split for rework (strip resist → re-litho → re-measure).
      // In-place retry does not exist in semiconductor manufacturing —
      // PASS wafers continue forward, FAIL wafers split to a rework child lot.
      SplitAndRework(reworks.keySet,
        s"Split ${reworks.size} wafer(s) for rework: ${reworks.values.map(d => s"${d.waferId}: attempt ${d.attempt}/${d.maxRetries}").mkString("; ")}")
    } else {
      AdvanceToNextStep
    }
  }

  /**
   * Select the first available fallback equipment area for a routing step.
   *
   * @param step             the routing step whose primary equipment is unavailable
   * @param unavailableAreas set of area IDs that are currently unavailable
   * @return Some(fallback) if a fallback is available, None otherwise
   */
  def selectFallbackArea(step: RoutingStep, unavailableAreas: Set[String]): Option[EquipmentArea] =
    step.fallbackAreas.find(fb => !unavailableAreas.contains(fb.areaId))

  /**
   * Calculate how many times this area has been visited before.
   *
   * 0 = first visit, 1 = first reentry, 2 = second reentry, etc.
   */
  def calculateReentryIndex(areaId: String, visitedSteps: Seq[String]): Int =
    visitedSteps.count(_ == areaId)
}
