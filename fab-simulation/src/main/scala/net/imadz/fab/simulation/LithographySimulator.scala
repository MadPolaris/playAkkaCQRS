package net.imadz.fab.simulation

import net.imadz.fab.protocol._

import scala.util.Random

/**
 * Lithography exposure tool simulator.
 *
 * Generates per-wafer lithography process measurements with configurable
 * defect probabilities. Common lithography failure modes:
 *   - alignment error: wafer misaligned → overlay shift
 *   - resist failure: uneven coating or development → pattern defects
 *   - hardware fault: scanner malfunction → job abort
 */
class LithographySimulator(
  val config: LithoConfig
) extends EquipmentSimulator {

  override protected def generateResult(
    state: SimState, job: Job, equipConfig: EquipmentConfig
  ): EquipmentResult = {
    val waferCount = config.waferCount // typically 5 for demo, 25 for production
    val wafers = (1 to waferCount).map { i =>
      val waferId = s"WAFER-$i"
      waferId -> LithoMeasurement(
        waferId = waferId,
        alignmentErrorNm = maybeFault(config.alignmentErrorRate, config.alignmentErrorNm),
        resistThicknessNm = maybeFault(config.resistFailureRate, config.resistFailureThicknessNm),
        focusErrorNm = maybeFault(config.hardwareFaultRate, config.focusErrorNm)
      )
    }.toMap
    LithoExposureResult(job.jobId, job.recipeId, wafers)
  }

  private def maybeFault(rate: Double, value: Double): Option[Double] =
    if (rng.nextDouble() < rate) Some(value * (0.8 + rng.nextDouble() * 0.4)) // ±20% jitter
    else None
}

case class LithoConfig(
  waferCount: Int = 5,
  alignmentErrorRate: Double = 0.10,   // 10% chance per wafer
  alignmentErrorNm: Double = 2.5,
  resistFailureRate: Double = 0.05,     // 5% chance per wafer
  resistFailureThicknessNm: Double = 120.0,
  hardwareFaultRate: Double = 0.02,     // 2% chance per wafer
  focusErrorNm: Double = 15.0
)
