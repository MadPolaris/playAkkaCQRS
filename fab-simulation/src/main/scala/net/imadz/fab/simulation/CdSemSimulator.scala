package net.imadz.fab.simulation

import net.imadz.fab.protocol._
import org.slf4j.LoggerFactory

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

  private val logger = LoggerFactory.getLogger(getClass)
  private val rng = new Random()

  override protected def generateResult(
    state: SimState, job: Job, equipConfig: EquipmentConfig
  ): EquipmentResult = {
    val wafers = config.waferIds.map { waferId =>
      val category = config.waferOutcomes.getOrElse(waferId, drawCategory())
      val measuredCd = generateCdValue(category)
      logger.info(s"[CdSemSimulator] Wafer $waferId → category=$category cdNm=$measuredCd (outcomeMap: ${config.waferOutcomes})")
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
    category match {
      case "PASS"       => target
      case "BORDERLINE" => target + config.borderlineOffsetNm
      case "FAIL"       => target + config.failOffsetNm
      case "SCRAP"      => target * config.scrapFactor
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
) {
  /** Generate a random CD value based on the configured yield rates. */
  def generateRandomCdValue(): Double = {
    val rng = scala.util.Random
    val roll = rng.nextDouble()
    val rate = passRate + borderlineRate + failRate + scrapRate
    val passEnd = passRate / rate
    val bdEnd = passEnd + borderlineRate / rate
    val failEnd = bdEnd + failRate / rate
    if (roll < passEnd) targetCdNm + rng.nextGaussian() * spreadNm
    else if (roll < bdEnd) targetCdNm + borderlineOffsetNm + rng.nextGaussian() * spreadNm * 0.5
    else if (roll < failEnd) targetCdNm + failOffsetNm + rng.nextGaussian() * spreadNm * 0.7
    else targetCdNm * scrapFactor + rng.nextGaussian() * spreadNm
  }
}