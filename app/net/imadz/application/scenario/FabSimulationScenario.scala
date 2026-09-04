package net.imadz.application.scenario

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
        scrapRate = 0.02,
        waferOutcomes = Map(
          "WAFER-1" -> "PASS",
          "WAFER-2" -> "PASS",
          "WAFER-3" -> "FAIL",
          "WAFER-4" -> "FAIL",
          "WAFER-5" -> "SCRAP"
        )
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

  /** B: Send-Ahead Pilot Run — split 1 pilot wafer, verify, then continue main lot */
  val sendAheadPilot: FabSimulationScenario = {
    val waferIds = (1 to 5).map(i => s"PILOT-WAFER-$i")
    FabSimulationScenario(
      scenarioId = "send-ahead-pilot",
      name = "Send-Ahead Pilot Run (5 wafers)",
      description = "Split WAFER-1 as Pilot → Litho+CDSEM → PASS → Merge → Main lot continues → SealComplete",
      lotSize = 5,
      waferIds = waferIds,
      litho = EquipmentConfig("LITHO-01", "LITHO", processingTime = 8.seconds),
      lithoDetail = LithoConfig(
        waferCount = 5,
        alignmentErrorRate = 0.05,
        resistFailureRate = 0.02,
        hardwareFaultRate = 0.01
      ),
      cdSem = EquipmentConfig("CDSEM-01", "METROLOGY", processingTime = 5.seconds),
      cdSemDetail = CdSemConfig(
        waferIds = waferIds,
        targetCdNm = 32.0,
        passRate = 0.90,
        borderlineRate = 0.05,
        failRate = 0.03,
        scrapRate = 0.02,
        waferOutcomes = Map(
          "PILOT-WAFER-1" -> "PASS",
          "PILOT-WAFER-2" -> "PASS",
          "PILOT-WAFER-3" -> "PASS",
          "PILOT-WAFER-4" -> "PASS",
          "PILOT-WAFER-5" -> "PASS"
        )
      ),
      amhs = AmhsConfig(
        routes = Map(
          ("STOCKER", "LITHO")     -> 3.seconds,
          ("LITHO", "CDSEM")       -> 2.seconds,
          ("CDSEM", "STOCKER")     -> 3.seconds,
          ("CDSEM", "LITHO")       -> 2.seconds
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

  /** C: Scrap & Downgrade — 1 wafer scrapped directly, no child lot */
  val scrapDowngrade: FabSimulationScenario = {
    val waferIds = (1 to 3).map(i => s"SCRP-WAFER-$i")
    FabSimulationScenario(
      scenarioId = "scrap-downgrade",
      name = "Scrap & Downgrade (3 wafers)",
      description = "Litho → CDSEM → WAFER-3 SCRAP, WAFER-1/2 PASS → SealComplete (no merge)",
      lotSize = 3,
      waferIds = waferIds,
      litho = EquipmentConfig("LITHO-01", "LITHO", processingTime = 6.seconds),
      lithoDetail = LithoConfig(
        waferCount = 3,
        alignmentErrorRate = 0.05,
        resistFailureRate = 0.02,
        hardwareFaultRate = 0.01
      ),
      cdSem = EquipmentConfig("CDSEM-01", "METROLOGY", processingTime = 4.seconds),
      cdSemDetail = CdSemConfig(
        waferIds = waferIds,
        targetCdNm = 32.0,
        passRate = 0.85,
        borderlineRate = 0.05,
        failRate = 0.05,
        scrapRate = 0.05,
        waferOutcomes = Map(
          "SCRP-WAFER-1" -> "PASS",
          "SCRP-WAFER-2" -> "PASS",
          "SCRP-WAFER-3" -> "SCRAP"
        )
      ),
      amhs = AmhsConfig(
        routes = Map(
          ("STOCKER", "LITHO")     -> 2.seconds,
          ("LITHO", "CDSEM")       -> 2.seconds,
          ("CDSEM", "STOCKER")     -> 2.seconds,
          ("CDSEM", "LITHO")       -> 2.seconds
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

  /** D: Metrology Sampling — split 2 for measurement, 4 skip */
  val samplingDemo: FabSimulationScenario = {
    val waferIds = (1 to 6).map(i => s"SMP-WAFER-$i")
    FabSimulationScenario(
      scenarioId = "sampling-demo",
      name = "Metrology Sampling (6 wafers)",
      description = "Split WAFER-1/2→SMP Lot → CDSEM measure → PASS → Merge; WAFER-3/4/5/6 skip measure",
      lotSize = 6,
      waferIds = waferIds,
      litho = EquipmentConfig("LITHO-01", "LITHO", processingTime = 8.seconds),
      lithoDetail = LithoConfig(
        waferCount = 6,
        alignmentErrorRate = 0.05,
        resistFailureRate = 0.02,
        hardwareFaultRate = 0.01
      ),
      cdSem = EquipmentConfig("CDSEM-01", "METROLOGY", processingTime = 5.seconds),
      cdSemDetail = CdSemConfig(
        waferIds = waferIds,
        targetCdNm = 32.0,
        passRate = 0.90,
        borderlineRate = 0.05,
        failRate = 0.03,
        scrapRate = 0.02,
        waferOutcomes = Map(
          "SMP-WAFER-1" -> "PASS",
          "SMP-WAFER-2" -> "PASS",
          "SMP-WAFER-3" -> "PASS",
          "SMP-WAFER-4" -> "PASS",
          "SMP-WAFER-5" -> "PASS",
          "SMP-WAFER-6" -> "PASS"
        )
      ),
      amhs = AmhsConfig(
        routes = Map(
          ("STOCKER", "LITHO")     -> 2.seconds,
          ("LITHO", "CDSEM")       -> 2.seconds,
          ("CDSEM", "STOCKER")     -> 2.seconds,
          ("STOCKER", "CDSEM")     -> 2.seconds,  // sampling direct path
          ("CDSEM", "LITHO")       -> 2.seconds
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

  /** E: Hold & Release — 1 wafer borderline → hold → review → release → merge */
  val holdRelease: FabSimulationScenario = {
    val waferIds = (1 to 5).map(i => s"HLD-WAFER-$i")
    FabSimulationScenario(
      scenarioId = "hold-release",
      name = "Hold & Release (5 wafers)",
      description = "WAFER-3 BORDERLINE → Split→Hold→15s Engineer Review→Release→Merge; others PASS normally",
      lotSize = 5,
      waferIds = waferIds,
      litho = EquipmentConfig("LITHO-01", "LITHO", processingTime = 8.seconds),
      lithoDetail = LithoConfig(
        waferCount = 5,
        alignmentErrorRate = 0.05,
        resistFailureRate = 0.02,
        hardwareFaultRate = 0.01
      ),
      cdSem = EquipmentConfig("CDSEM-01", "METROLOGY", processingTime = 5.seconds),
      cdSemDetail = CdSemConfig(
        waferIds = waferIds,
        targetCdNm = 32.0,
        passRate = 0.80,
        borderlineRate = 0.12,
        failRate = 0.05,
        scrapRate = 0.03,
        waferOutcomes = Map(
          "HLD-WAFER-1" -> "PASS",
          "HLD-WAFER-2" -> "PASS",
          "HLD-WAFER-3" -> "BORDERLINE",
          "HLD-WAFER-4" -> "PASS",
          "HLD-WAFER-5" -> "PASS"
        )
      ),
      amhs = AmhsConfig(
        routes = Map(
          ("STOCKER", "LITHO")     -> 3.seconds,
          ("LITHO", "CDSEM")       -> 2.seconds,
          ("CDSEM", "STOCKER")     -> 3.seconds,
          ("CDSEM", "LITHO")       -> 2.seconds
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
