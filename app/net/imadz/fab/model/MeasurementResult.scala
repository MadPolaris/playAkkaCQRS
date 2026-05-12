package net.imadz.fab.model

/**
 * Wafer measurement value objects.
 *
 * Design principle: measurements are pure data ("CD measured 33.5nm").
 * Spec thresholds belong to DecisionConfig. Classification/disposition
 * is the responsibility of FabMeasurementClassifier.
 */
case class CriticalDimension(
  waferId: String,
  measuredNm: Double,
  targetNm: Double = 32.0
)

case class LithoMeasurement(
  waferId: String,
  alignmentErrorNm: Option[Double] = None,
  resistThicknessNm: Option[Double] = None,
  focusErrorNm: Option[Double] = None
)

/** Per-wafer measurement aggregation for a completed step */
case class WaferMeasurement(
  waferId: String,
  stepId: String,
  lithoResult: Option[LithoMeasurement] = None,
  cdResult: Option[CriticalDimension] = None
)
