package net.imadz.fab.events

import net.imadz.domain.entities.FabProcessEntity._

/**
 * Maps FabProcessEvent (domain) → List[FabSimulationEvent] (WebSocket).
 *
 * Shared by FabDemoEventBridge (real-time) and future Akka Projection (replay).
 * Simulation-only events (EquipmentStateChanged, FoupInTransit, OrchestratorCommand,
 * FoupStateChanged, GlobalStatusChanged, LedgerStepAdvanced) are NOT domain events
 * and are published directly by the Coordinator.
 *
 * @param scenarioId  the scenario lot ID
 * @param scenarioName the human-readable scenario name
 * @param waferIds    all wafer IDs in the scenario
 * @param lotSize     total number of wafers
 */
class ProcessEventMapper(
  scenarioId: String,
  scenarioName: String,
  waferIds: Seq[String],
  lotSize: Int
) {

  def mapToFabSimulationEvent(event: FabProcessEvent): List[FabSimulationEvent] = event match {
    case ProcessStarted(_, _, _) =>
      List(DemoStarted(scenarioId, scenarioName, lotSize, waferIds))

    case FoupLoaded(foupId, stockerId) =>
      List(
        FoupArrivedAtPort(foupId, stockerId, "STOCKER-PORT-1"),
        AggregateStateUpdated(
          LotStateSnapshot(scenarioId, "Active", lotSize, passCount = 0, scrapCount = 0),
          None,
          waferIds.map(wid => WaferStateSnapshot(wid, "Active", scenarioId, "Pending", 0))
        )
      )

    case TransportStarted(foupId, fromArea, toArea, estimatedMs) =>
      List(
        FoupInTransit(foupId, fromArea, toArea, estimatedMs / 2)
      )

    case TransportCompleted(foupId, equipmentId) =>
      val areaId = equipmentId.replace("-01", "")
      List(
        FoupArrivedAtPort(foupId, equipmentId, s"${equipmentId}-PORT-1"),
        EquipmentStateChanged(equipmentId, areaId, "Idle", None)
      )

    case EquipmentJobStarted(equipmentId, recipeId) =>
      val areaId = equipmentId.replace("-01", "")
      List(
        ProcessingStarted(equipmentId, recipeId, estimatedMs = 2000L),
        EquipmentStateChanged(equipmentId, areaId, "Busy", Some(s"job-$recipeId"))
      )

    case EquipmentJobCompleted(equipmentId, jobId, success) =>
      val areaId = equipmentId.replace("-01", "")
      List(
        ProcessingCompleted(equipmentId, jobId, success, ""),
        EquipmentStateChanged(equipmentId, areaId, "Idle", None)
      )

    case WaferMeasured(waferId, cdNm) =>
      List(MeasurementResultEvent(waferId, cdNm, "PENDING", 34.0))

    case WaferClassified(waferId, classification, reworkCount, cdValue) =>
      val decisionAction = classification match {
        case "PASS" => "PASS → Continue"
        case "BORDERLINE" => "BORDERLINE → Conditional Pass"
        case "FAIL" => s"FAIL → Rework (attempt $reworkCount)"
        case "SCRAP" => "SCRAP → Terminate"
        case _ => classification
      }
      val events = List.newBuilder[FabSimulationEvent]
      events += DecisionMade(waferId, decisionAction, None)
      events += MeasurementResultEvent(waferId, cdValue, classification, 34.0)
      if (classification == "SCRAP") {
        events += ScrapEvent(waferId, s"CD=$cdValue nm → SCRAP")
      }
      events.result()

    case WafersSplitForRework(reworkWaferIds, scrapWaferIds, iteration) =>
      val sagaId = s"SAGA-SPLIT-$iteration"
      val rwkLotId = s"$scenarioId-RWK"
      List(
        SagaOperationEvent(sagaId, "SplitLot", "PREPARE", scenarioId, rwkLotId, reworkWaferIds.toSeq),
        SagaOperationEvent(sagaId, "SplitLot", "COMMITTED", scenarioId, rwkLotId, reworkWaferIds.toSeq)
      ) ++ (if (scrapWaferIds.nonEmpty) scrapWaferIds.toSeq.map(wid => ScrapEvent(wid, "Max rework exceeded")) else Nil)

    case WafersReworked(waferIds) =>
      Nil // rework cycle markers, no UI event needed

    case ProcessCompleted(lotId, passCount, scrapCount, reworkCount) =>
      List(
        DemoCompleted(lotId, lotSize, passCount, reworkCount, scrapCount),
        LotUpdated(lotId, activeWafers = 0, scrappedWafers = scrapCount,
          completedSteps = List("Load", "Litho", "CD-SEM", "Classify", "Complete"),
          passedWafers = passCount, reworkedWafers = reworkCount)
      )
  }

  /** Builds an AggregateStateUpdated from raw data (used by Coordinator for derived updates). */
  def buildAggregateState(
    passCount: Int,
    scrapCount: Int,
    reworkActive: Boolean,
    waferStates: Seq[(String, String, String, Int)],
    reworkWaferCount: Int = 0
  ): AggregateStateUpdated = {
    val sourceLot = LotStateSnapshot(scenarioId, "Active", lotSize, passCount, scrapCount)
    val reworkLot = if (reworkActive) {
      Some(LotStateSnapshot(s"$scenarioId-RWK", "Active", reworkWaferCount, 0, 0))
    } else None

    val wafers = waferStates.map { case (wid, status, cls, rwkCnt) =>
      val lotId = if (reworkActive && cls == "FAIL" && rwkCnt > 0) s"$scenarioId-RWK" else scenarioId
      WaferStateSnapshot(wid, status, lotId, cls, rwkCnt)
    }

    AggregateStateUpdated(sourceLot, reworkLot, wafers)
  }

  def buildFinalAggregateState(passCount: Int, scrapCount: Int, reworkCount: Int): AggregateStateUpdated = {
    val sourceLot = LotStateSnapshot(scenarioId, "Sealed", lotSize, passCount, scrapCount)
    val wafers = waferIds.map { wid =>
      WaferStateSnapshot(wid, "Active", scenarioId, "PASS", reworkCount = 0)
    }
    AggregateStateUpdated(sourceLot, None, wafers)
  }
}
