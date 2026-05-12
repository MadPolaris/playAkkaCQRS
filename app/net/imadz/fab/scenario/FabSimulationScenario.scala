package net.imadz.fab.scenario

import net.imadz.fab.simulation._

import scala.concurrent.duration._

/**
 * Complete simulation scenario definition.
 *
 * Describes one manufacturing flow: equipment configs, decision thresholds,
 * and the route through factory areas. A scenario is serializable and can be
 * registered and selected at runtime.
 */
case class FabSimulationScenario(
  scenarioId: String,
  name: String,
  description: String,
  // Fab layout
  lotSize: Int,
  waferIds: Seq[String],
  // Equipment configs
  litho: EquipmentConfig,
  lithoDetail: LithoConfig,
  cdSem: EquipmentConfig,
  cdSemDetail: CdSemConfig,
  amhs: AmhsConfig,
  stocker: StockerConfig,
  // Decision config
  decision: DecisionConfig
)

/**
 * Decision thresholds for wafer disposition.
 *
 * These are the process specification limits — the FabMeasurementClassifier
 * uses them to decide PASS/BORDERLINE/FAIL/SCRAP for each measurement.
 */
case class DecisionConfig(
  lowerSpecNm: Double,
  upperSpecNm: Double,
  /** Margin above upperSpec where measurement is still BORDERLINE (not FAIL) */
  borderlineWindowNm: Double,
  /** Max rework attempts before forced scrap */
  maxReworkCount: Int = 2,
  /** Recipe ID for rework */
  reworkRecipeId: String = "REWORK-LITHO-001"
)

// ============================================================================
// Pre-defined scenarios
// ============================================================================

object StandardScenarios {

  /** Minimal 5-wafer Litho cell demo */
  val photoCell5Wafer: FabSimulationScenario = {
    val waferIds = (1 to 5).map(i => s"WAFER-$i")
    FabSimulationScenario(
      scenarioId = "photo-cell-5wafer",
      name = "Lithography Photo Cell (5 wafers)",
      description = "Stocker → AMHS → Litho Scanner → AMHS → CD-SEM → Decision (PASS/BORDERLINE/FAIL/SCRAP)",
      lotSize = 5,
      waferIds = waferIds,
      litho = EquipmentConfig("LITHO-01", "LITHO", processingTime = 8.seconds),
      lithoDetail = LithoConfig(
        waferCount = 5,
        alignmentErrorRate = 0.10,
        resistFailureRate = 0.05,
        hardwareFaultRate = 0.02
      ),
      cdSem = EquipmentConfig("CDSEM-01", "METROLOGY", processingTime = 5.seconds),
      cdSemDetail = CdSemConfig(
        waferIds = waferIds,
        targetCdNm = 32.0,
        passRate = 0.80,
        borderlineRate = 0.10,
        failRate = 0.08,
        scrapRate = 0.02
      ),
      amhs = AmhsConfig(
        routes = Map(
          ("STOCKER", "LITHO")     -> 3.seconds,
          ("LITHO", "CDSEM")       -> 2.seconds,
          ("CDSEM", "STOCKER")     -> 3.seconds,
          ("CDSEM", "LITHO")       -> 2.seconds   // rework path
        ),
        maxConcurrentTransports = 3
      ),
      stocker = StockerConfig("STOCKER-01", portCount = 4, loadTime = 2.seconds),
      decision = DecisionConfig(
        lowerSpecNm = 28.0,
        upperSpecNm = 34.0,
        borderlineWindowNm = 2.0,
        maxReworkCount = 2,
        reworkRecipeId = "REWORK-LITHO-001"
      )
    )
  }
}
