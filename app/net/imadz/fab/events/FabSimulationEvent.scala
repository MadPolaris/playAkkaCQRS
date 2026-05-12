package net.imadz.fab.events

import net.imadz.common.CborSerializable

/**
 * WebSocket event types for the Fab simulation demo frontend.
 *
 * These are serialized to JSON and sent over WebSocket to the browser.
 * Each event type corresponds to a visible state change in the factory floor
 * or reactive system visualization.
 */
sealed trait FabSimulationEvent extends CborSerializable

// --- Lifecycle ---
case class DemoStarted(scenarioId: String, name: String, lotSize: Int, waferIds: Seq[String]) extends FabSimulationEvent
case class DemoCompleted(lotId: String, totalWafers: Int, passedWafers: Int, reworkedWafers: Int, scrappedWafers: Int) extends FabSimulationEvent
case object DemoPaused extends FabSimulationEvent
case object DemoResumed extends FabSimulationEvent

// --- Equipment State ---
case class EquipmentStateChanged(equipmentId: String, areaId: String, status: String, currentJob: Option[String]) extends FabSimulationEvent

// --- Material Handling ---
case class FoupInTransit(foupId: String, fromArea: String, toArea: String, etaMs: Long) extends FabSimulationEvent
case class FoupArrivedAtPort(foupId: String, equipmentId: String, portId: String) extends FabSimulationEvent
case class FoupDepartedFromPort(foupId: String, equipmentId: String, portId: String) extends FabSimulationEvent

// --- Processing ---
case class ProcessingStarted(equipmentId: String, recipeId: String, estimatedMs: Long) extends FabSimulationEvent
case class ProcessingCompleted(equipmentId: String, jobId: String, success: Boolean, detail: String) extends FabSimulationEvent

// --- Measurement & Decision ---
case class MeasurementResultEvent(waferId: String, cdNm: Double, classification: String, specLimit: Double) extends FabSimulationEvent
case class DecisionMade(waferId: String, action: String, detail: Option[String]) extends FabSimulationEvent

// --- Orchestrator Visibility ---
case class OrchestratorCommand(commandId: String, targetEquipmentId: String, commandType: String, description: String, relatedWaferIds: Seq[String] = Seq.empty) extends FabSimulationEvent

// --- FOUP State ---
case class FoupStateChanged(foupId: String, status: String, activeWaferCount: Int, reworkWaferCount: Int, location: String, lotId: String = "", reworkLotId: String = "") extends FabSimulationEvent

// --- Global Status (需求3: 工作状态指示) ---
case class GlobalStatusChanged(status: String, detail: String, phase: String) extends FabSimulationEvent

// --- Aggregate State (需求5: 业务聚合状态面板) ---
case class AggregateStateUpdated(
  sourceLot: LotStateSnapshot,
  reworkLot: Option[LotStateSnapshot],
  wafers: Seq[WaferStateSnapshot]
) extends FabSimulationEvent

case class LotStateSnapshot(lotId: String, status: String, waferCount: Int, passCount: Int, scrapCount: Int)
case class WaferStateSnapshot(waferId: String, status: String, lotId: String, classification: String, reworkCount: Int)

// --- Scrap Event (需求1: 报废去向) ---
case class ScrapEvent(waferId: String, reason: String) extends FabSimulationEvent

// --- Saga ---
case class SagaOperationEvent(transactionId: String, operation: String, status: String, sourceLotId: String = "", targetLotId: String = "", relatedWaferIds: Seq[String] = Seq.empty) extends FabSimulationEvent

// --- Lot Summary ---
case class LotUpdated(lotId: String, activeWafers: Int, scrappedWafers: Int, completedSteps: List[String], passedWafers: Int = 0, reworkedWafers: Int = 0) extends FabSimulationEvent

// --- Fault Injection ---
case class FaultInjected(equipmentId: String, faultType: String) extends FabSimulationEvent

// --- Event Sourcing Ledger ---
case class LedgerStepAdvanced(stepSeq: Int, stepName: String) extends FabSimulationEvent

// --- Domain Event Record (sidebar audit trail) ---
case class DomainEventRecorded(eventType: String, data: String, timestamp: Long) extends FabSimulationEvent
