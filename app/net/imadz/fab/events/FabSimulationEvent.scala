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

// --- Equipment State ---
case class EquipmentStateChanged(equipmentId: String, areaId: String, status: String, currentJob: Option[String]) extends FabSimulationEvent

// --- Material Handling ---
case class FoupInTransit(foupId: String, fromArea: String, toArea: String, etaMs: Long) extends FabSimulationEvent
case class FoupArrivedAtPort(foupId: String, equipmentId: String, portId: String) extends FabSimulationEvent

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
  childLots: Seq[LotStateSnapshot],
  wafers: Seq[WaferStateSnapshot]
) extends FabSimulationEvent

case class LotStateSnapshot(lotId: String, status: String, waferCount: Int, passCount: Int, scrapCount: Int, currentArea: String = "")
case class WaferStateSnapshot(waferId: String, status: String, lotId: String, classification: String, reworkCount: Int)


// --- OCAP ---
case class OcapActionTriggered(
  ruleId: String,
  ruleName: String,
  actionType: String,       // HOLD | REWORK | SCRAP | NOTIFY | ADJUST_RECIPE | COMPOSITE
  detail: String,
  affectedWafers: Seq[String] = Seq.empty
) extends FabSimulationEvent

// --- Scrap Event (需求1: 报废去向) ---
case class ScrapEvent(waferId: String, reason: String) extends FabSimulationEvent

// --- Saga ---
case class SagaOperationEvent(transactionId: String, operation: String, status: String, sourceLotId: String = "", targetLotId: String = "", relatedWaferIds: Seq[String] = Seq.empty) extends FabSimulationEvent

// --- Lot Summary ---
case class LotUpdated(lotId: String, activeWafers: Int, scrappedWafers: Int, completedSteps: List[String], passedWafers: Int = 0, reworkedWafers: Int = 0) extends FabSimulationEvent


// --- Event Sourcing Ledger ---
case class LedgerStepAdvanced(
  stepSeq: Int,
  stepName: String,
  currentNodeId: Option[String] = None,     // M3.5: RouteNode.nodeId currently executing
  activeSubProcess: Option[String] = None,  // M3.5: "ReworkLoop", "SendAheadPilot", etc.
  branchDecision: Option[String] = None     // M3.5: "PASS→Continue", "FAIL→Rework", etc.
) extends FabSimulationEvent

// --- Pipeline Stage Failure (M3.5) ---
case class PipelineStageFailed(
  stageName: String,
  equipId: Option[String],
  errorCode: String,
  detail: String,
  timestamp: Long = System.currentTimeMillis()
) extends FabSimulationEvent

// --- Domain Event Record (sidebar audit trail) ---
case class DomainEventRecorded(
  eventType: String,
  aggregateType: String,  // "Chain" | "Saga" | "Lot" | "Wafer" | "FabProcess" | "FabSagaTransaction"
  aggregateId: String,
  data: String,
  timestamp: Long,
  layer: Int             // 0=Chain, 1=Saga, 2=Aggregate, 3=Process
) extends FabSimulationEvent
