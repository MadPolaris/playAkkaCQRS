package net.imadz.application.component

import net.imadz.fab.protocol.CriticalDimension
import net.imadz.application.scenario.DecisionConfig
import net.imadz.m25.component._

import scala.concurrent.Future

/**
 * Fab-specific disposition classifier — implements M2.5+ ResultClassifier trait.
 *
 * Plugs directly into SubBatchPipeline's `classify` stage (stage 6) without
 * modifying any M2.5+ code. Maps Fab's four-way disposition into M2.5+'s
 * three-way Classification ADT:
 *
 *   PASS       → Success(waferId)
 *   BORDERLINE → Suspicious(waferId)      → routed to ReconfirmHandler
 *   FAIL       → Failure(waferId, RouteToArea(LITHO)) → ReBatchRouter rework
 *   SCRAP      → Failure(waferId, Scrap)  → ReBatchRouter scrap
 *
 * NOT responsible for: FDC (equipment health), SPC (process drift), raw data parsing.
 * Those are separate concerns that sit at different pipeline stages or in parallel pipelines.
 */
class FabMeasurementClassifier(config: DecisionConfig)
    extends ResultClassifier[CriticalDimension, String] {

  override def classify(
    rawResults: Seq[CriticalDimension],
    items: Seq[String]
  ): Future[Seq[Classification[String]]] = {

    val results: Seq[Classification[String]] = rawResults.zip(items).map {
      case (cd, waferId) =>
        val measured = cd.measuredNm

        // PASS: within spec limits
        if (measured >= config.lowerSpecNm && measured <= config.upperSpecNm) {
          Success[String](waferId, cd)
        }
        // BORDERLINE: slightly above upper spec, within borderline window
        else if (measured > config.upperSpecNm &&
                 measured <= config.upperSpecNm + config.borderlineWindowNm) {
          Suspicious[String](waferId,
            SuspiciousReason("BORDERLINE",
              s"CD $measured nm borderline (upper spec ${config.upperSpecNm} nm, window +${config.borderlineWindowNm} nm)"))
        }
        // SCRAP: far exceeds spec (> 1.5x upper spec)
        else if (measured > config.upperSpecNm * 1.5) {
          Failure[String](waferId,
            FailureReason("SCRAP",
              s"CD $measured nm far exceeds upper spec ${config.upperSpecNm} nm",
              Some(NextStep.Scrap)))
        }
        // FAIL: out of spec, can rework
        else {
          Failure[String](waferId,
            FailureReason("CD_OUT_OF_SPEC",
              s"CD $measured nm exceeds upper spec ${config.upperSpecNm} nm",
              Some(NextStep.RouteToArea("LITHO", Some(config.reworkRecipeId)))))
        }
    }

    Future.successful(results)
  }
}
