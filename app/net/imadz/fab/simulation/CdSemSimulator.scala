package net.imadz.fab.simulation

import net.imadz.fab.protocol._

import scala.util.Random

/**
 * CD-SEM (Critical Dimension Scanning Electron Microscope) metrology simulator.
 *
 * Generates per-wafer critical dimension measurements with configurable
 * yield probabilities. The classification (PASS/BORDERLINE/FAIL/SCRAP) is NOT
 * done here — CdSemSimulator only reports measured values. Classification is
 * the responsibility of FabMeasurementClassifier in the pipeline's classify stage.
 */
class CdSemSimulator(
  val config: CdSemConfig
) extends EquipmentSimulator {

  private val rng = new Random()

  override protected def generateResult(
    state: SimState, job: Job, equipConfig: EquipmentConfig
  ): EquipmentResult = {
    val wafers = config.waferIds.map { waferId =>
      val category = config.waferOutcomes.getOrElse(waferId, drawCategory())
      val measuredCd = generateCdValue(category)
      waferId -> CriticalDimension(waferId, measuredCd, config.targetCdNm)
    }.toMap
    MetrologyResult(job.jobId, wafers)
  }

  private def drawCategory(): String = {
    val roll = rng.nextDouble()
    if (roll < config.scrapRate) "SCRAP"
    else if (roll < config.scrapRate + config.failRate) "FAIL"
    else if (roll < config.scrapRate + config.failRate + config.borderlineRate) "BORDERLINE"
    else "PASS"
  }

  private def generateCdValue(category: String): Double = {
    val target = config.targetCdNm
    val spread = config.spreadNm
    category match {
      case "PASS"       => target + rng.nextGaussian() * spread
      case "BORDERLINE" => target + config.borderlineOffsetNm + rng.nextGaussian() * spread * 0.5
      case "FAIL"       => target + config.failOffsetNm + rng.nextGaussian() * spread * 0.7
      case "SCRAP"      => target * config.scrapFactor + rng.nextGaussian() * spread
      case _            => target
    }
  }
}

case class CdSemConfig(
  waferIds: Seq[String] = (1 to 5).map(i => s"WAFER-$i"),
  targetCdNm: Double = 32.0,
  spreadNm: Double = 1.5,           // natural variation (1-sigma)
  passRate: Double = 0.80,          // within spec
  borderlineRate: Double = 0.10,    // slightly out of spec
  failRate: Double = 0.08,          // out of spec, can rework
  scrapRate: Double = 0.02,         // far out of spec, must scrap
  borderlineOffsetNm: Double = 3.0, // nm above target for borderline
  failOffsetNm: Double = 6.0,       // nm above target for fail
  scrapFactor: Double = 1.5,        // multiplier on target for scrap
  // Deterministic per-wafer outcomes for demo scenarios (first pass only)
  waferOutcomes: Map[String, String] = Map.empty
)
