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

// --- Saga ---
case class SagaOperationEvent(transactionId: String, operation: String, status: String) extends FabSimulationEvent

// --- Lot Summary ---
case class LotUpdated(lotId: String, activeWafers: Int, scrappedWafers: Int, completedSteps: List[String]) extends FabSimulationEvent

// --- Fault Injection ---
case class FaultInjected(equipmentId: String, faultType: String) extends FabSimulationEvent
