package net.imadz.fab.events

import net.imadz.domain.entities.FabProcessEntity._

import scala.collection.mutable

/**
 * Maps FabProcessEvent (domain) → List[FabSimulationEvent] (WebSocket).
 *
 * Shared by FabDemoEventBridge (real-time) and future Akka Projection (replay).
 * Simulation-only events (EquipmentStateChanged, FoupInTransit, OrchestratorCommand,
 * FoupStateChanged, GlobalStatusChanged, LedgerStepAdvanced) are NOT domain events
 * and are published directly by the Coordinator.
 *
 * Maintains cumulative aggregate state across multiple process events so that
 * [[AggregateStateUpdated]] can be published after each [[WaferClassified]] — the
 * domain-event panel is driven exclusively through the Projection → EventStream
 * → FabDemoEventBridge chain.
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

  // ---- cumulative aggregate state (mutable, updated per event) ----
  private var passCount: Int = 0
  private var scrapCount: Int = 0
  private var reworkCount: Int = 0
  private val waferStatus: mutable.Map[String, String] = mutable.Map.empty  // waferId → classification
  private val waferRework: mutable.Map[String, Int] = mutable.Map.empty     // waferId → reworkCount

  // ---- per-wafer CD values (latest measurement) ----
  private val waferCdValues: mutable.Map[String, Double] = mutable.Map.empty

  def mapToFabSimulationEvent(event: FabProcessEvent): List[FabSimulationEvent] = event match {
    case ProcessStarted(_, _, _) =>
      List(DemoStarted(scenarioId, scenarioName, lotSize, waferIds))

    case FoupLoaded(foupId, stockerId) =>
      // Initialise tracking state
      waferIds.foreach { wid =>
        waferStatus(wid) = "Pending"
        waferRework(wid) = 0
      }
      List(
        FoupArrivedAtPort(foupId, stockerId, "STOCKER-PORT-1"),
        buildAggregateState()
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
      waferCdValues(waferId) = cdNm
      List(MeasurementResultEvent(waferId, cdNm, "PENDING", 34.0))

    case WaferClassified(waferId, classification, reworkCount, cdValue) =>
      // Update cumulative state
      classification match {
        case "PASS"    => passCount += 1
        case "SCRAP"   => scrapCount += 1
        case _         => // FAIL, BORDERLINE, etc — no immediate pass/scrap
      }
      if (reworkCount > 0) this.reworkCount += 1
      waferStatus(waferId) = classification
      waferRework(waferId) = reworkCount

      val decisionAction = classification match {
        case "PASS"        => "PASS → Continue"
        case "BORDERLINE"  => "BORDERLINE → Conditional Pass"
        case "FAIL"        => s"FAIL → Rework (attempt $reworkCount)"
        case "SCRAP"       => "SCRAP → Terminate"
        case other         => other
      }
      val events = List.newBuilder[FabSimulationEvent]
      events += DecisionMade(waferId, decisionAction, None)
      events += MeasurementResultEvent(waferId, cdValue, classification, 34.0)
      if (classification == "SCRAP") {
        events += ScrapEvent(waferId, s"CD=$cdValue nm → SCRAP")
      }
      events += buildAggregateState()
      events.result()

    case WafersSplitForRework(reworkWaferIds, scrapWaferIds, iteration) =>
      val sagaId = s"SAGA-SPLIT-$iteration"
      val rwkLotId = s"$scenarioId-RWK"
      // Update tracking: rework wafers classified as FAIL
      reworkWaferIds.foreach { wid =>
        waferStatus(wid) = "FAIL"
        if (!waferRework.contains(wid)) waferRework(wid) = 0
        waferRework(wid) = waferRework(wid) + 1
      }
      scrapWaferIds.foreach { wid =>
        waferStatus(wid) = "SCRAP"
        scrapCount += 1
      }
      List(
        SagaOperationEvent(sagaId, "SplitLot", "PREPARE", scenarioId, rwkLotId, reworkWaferIds.toSeq),
        SagaOperationEvent(sagaId, "SplitLot", "COMMITTED", scenarioId, rwkLotId, reworkWaferIds.toSeq),
        buildAggregateState()
      )

    case WafersReworked(waferIds) =>
      Nil // rework cycle markers, no UI event needed

    case ProcessCompleted(lotId, passCount, scrapCount, reworkCount) =>
      // Use the aggregate-level counts from the event (authoritative)
      this.passCount = passCount
      this.scrapCount = scrapCount
      this.reworkCount = reworkCount
      List(
        DemoCompleted(lotId, lotSize, passCount, reworkCount, scrapCount),
        LotUpdated(lotId, activeWafers = 0, scrappedWafers = scrapCount,
          completedSteps = List("Load", "Litho", "CD-SEM", "Classify", "Complete"),
          passedWafers = passCount, reworkedWafers = reworkCount),
        buildAggregateState()
      )

    // Pass-through events (no UI mapping needed)
    case WafersSentAsPilot(_) | WafersSampled(_, _) | WafersHeld(_, _) | WafersReleased(_) =>
      Nil
  }

  /** Builds an AggregateStateUpdated from the current cumulative tracking state. */
  def buildAggregateState(): AggregateStateUpdated = {
    val sourceLot = LotStateSnapshot(scenarioId, "Active", lotSize, passCount, scrapCount)
    val wafers = waferIds.map { wid =>
      val status = waferStatus.getOrElse(wid, "Active")
      val classification = if (status == "Pending") "Pending" else status
      WaferStateSnapshot(wid, status, scenarioId, classification, waferRework.getOrElse(wid, 0))
    }
    AggregateStateUpdated(sourceLot, None, wafers)
  }

  /** Builds final AggregateStateUpdated for completed processes. */
  def buildFinalAggregateState(): AggregateStateUpdated = {
    val sourceLot = LotStateSnapshot(scenarioId, "Sealed", lotSize, passCount, scrapCount)
    val wafers = waferIds.map { wid =>
      WaferStateSnapshot(wid, "Active", scenarioId,
        waferStatus.getOrElse(wid, "PASS"), waferRework.getOrElse(wid, 0))
    }
    AggregateStateUpdated(sourceLot, None, wafers)
  }
}
