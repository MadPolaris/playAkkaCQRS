package net.imadz.fab.model

import net.imadz.fab.protocol.{CriticalDimension, LithoMeasurement}

/**
 * Wafer measurement value objects.
 *
 * Design principle: measurements are pure data ("CD measured 33.5nm").
 * Spec thresholds belong to DecisionConfig. Classification/disposition
 * is the responsibility of FabMeasurementClassifier.
 */
/** Per-wafer measurement aggregation for a completed step */
case class WaferMeasurement(
  waferId: String,
  stepId: String,
  lithoResult: Option[LithoMeasurement] = None,
  cdResult: Option[CriticalDimension] = None
)
