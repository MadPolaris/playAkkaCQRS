package net.imadz.fab.orchestration

import net.imadz.common.CommonTypes.Id
import net.imadz.fab.component.FabMeasurementClassifier
import net.imadz.fab.protocol._
import net.imadz.fab.scenario.DecisionConfig
import net.imadz.m25.component._

import scala.concurrent.{ExecutionContext, Future}

/**
 * Dynamic Flow Assembler — the M3 decision brain.
 *
 * Given the equipment result and lot context, decides what happens next.
 * Replaces the synchronous recursion in the old FabFlowEngine.
 *
 * Decision table:
 *   PASS       → ContinueToNextStep (advance to next process step)
 *   BORDERLINE → ReconfirmDecision (re-measure or manual review)
 *   FAIL       → ReworkWafers (trigger Saga split → rework child lot)
 *   SCRAP      → ScrapWafers (trigger direct wafer scrap)
 *   Rework count exceeded → forced Scrap
 */
class DynamicFlowAssembler(
  private val classifier: FabMeasurementClassifier,
  private val decisionConfig: DecisionConfig
) {

  /**
   * Decide the next action for a completed process step.
   *
   * @param lotId        the current lot
   * @param waferIds     all wafers in the current batch
   * @param result       the equipment result from the just-completed step
   * @param reworkCounts per-wafer rework attempt counter
   */
  def decideNextStep(
    lotId: Id,
    waferIds: Seq[String],
    result: EquipmentResult,
    reworkCounts: Map[String, Int] = Map.empty
  )(implicit ec: ExecutionContext): Future[BatchDecision] = {

    import ClassificationOps._

    val (rawResults, measurementIds) = extractMeasurements(result)

    classifier.classify(rawResults, measurementIds).map { classifications =>
      val decisions = classifications.map { c =>
        val waferId = c.waferId
        val reworkCount = reworkCounts.getOrElse(waferId, 0)
        val wc = c.toWaferClassification
        decideWafer(waferId, wc, reworkCount)
      }
      BatchDecision(decisions)
    }
  }

  private def decideWafer(waferId: String, c: WaferClassification, reworkCount: Int): WaferDecision = {
    c match {
      case WaferClassification.Pass =>
        WaferDecision(waferId, NextAction.Advance, None)

      case WaferClassification.Borderline(reason) =>
        WaferDecision(waferId, NextAction.Reconfirm, Some(reason))

      case WaferClassification.Fail(reason) =>
        if (reworkCount >= decisionConfig.maxReworkCount)
          WaferDecision(waferId, NextAction.Scrap,
            Some(s"Rework limit ($reworkCount/${decisionConfig.maxReworkCount}) exceeded: $reason"))
        else
          WaferDecision(waferId, NextAction.Rework(decisionConfig.reworkRecipeId), Some(reason))

      case WaferClassification.Scrap(reason) =>
        WaferDecision(waferId, NextAction.Scrap, Some(reason))
    }
  }

  private def extractMeasurements(result: EquipmentResult): (Seq[CriticalDimension], Seq[String]) = {
    result match {
      case MetrologyResult(_, wafers) =>
        val dims = wafers.values.toSeq
        (dims, dims.map(_.waferId))
      case _ =>
        (Seq.empty, Seq.empty)
    }
  }
}

// ============================================================================
// Decision Model
// ============================================================================

/** Decision for a batch of wafers after classification */
case class BatchDecision(perWafer: Seq[WaferDecision]) {
  def advanceWafers: Seq[WaferDecision] = perWafer.filter(_.action == NextAction.Advance)
  def reworkWafers: Seq[WaferDecision] = perWafer.filter(_.action.isInstanceOf[NextAction.Rework])
  def scrapWafers: Seq[WaferDecision] = perWafer.filter(_.action == NextAction.Scrap)
  def reconfirmWafers: Seq[WaferDecision] = perWafer.filter(_.action == NextAction.Reconfirm)
}

/** Per-wafer decision */
case class WaferDecision(waferId: String, action: NextAction, detail: Option[String])

/** What to do next with a wafer */
sealed trait NextAction
object NextAction {
  /** Continue to next process step */
  case object Advance extends NextAction
  /** Hold for manual/automated reconfirmation */
  case object Reconfirm extends NextAction
  /** Rework wafer with given recipe */
  case class Rework(recipeId: String) extends NextAction
  /** Scrap the wafer */
  case object Scrap extends NextAction
}

// ============================================================================
// Classification helper — maps M2.5+ Classification back to Fab domain
// ============================================================================

sealed trait WaferClassification
object WaferClassification {
  case object Pass extends WaferClassification
  case class Borderline(reason: String) extends WaferClassification
  case class Fail(reason: String) extends WaferClassification
  case class Scrap(reason: String) extends WaferClassification
}

object ClassificationOps {
  implicit class RichClassification(val c: Classification[String]) extends AnyVal {
    def waferId: String = c match {
      case Success(item, _) => item
      case Failure(item, _) => item
      case Suspicious(item, _) => item
    }

    def toWaferClassification: WaferClassification = c match {
      case Success(_, _) => WaferClassification.Pass
      case Suspicious(_, SuspiciousReason(_, msg)) => WaferClassification.Borderline(msg)
      case Failure(_, FailureReason("SCRAP", msg, _)) => WaferClassification.Scrap(msg)
      case Failure(_, FailureReason(code, msg, _)) => WaferClassification.Fail(s"[$code] $msg")
    }
  }
}
