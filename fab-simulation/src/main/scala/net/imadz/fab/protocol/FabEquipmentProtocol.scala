package net.imadz.fab.protocol

import scala.concurrent.duration.FiniteDuration

// ============================================================================
// Equipment Command Model — commands sent TO equipment
// ============================================================================

sealed trait EquipmentCommand

/** Load a FOUP onto a specific equipment port. slotMap: port/slot → waferId */
case class LoadFoup(foupId: String, portId: String, slotMap: Map[Int, String]) extends EquipmentCommand

/** Unload a FOUP from an equipment port */
case class UnloadFoup(foupId: String, portId: String) extends EquipmentCommand

/** Start processing a recipe on the loaded wafers */
case class ProcessRecipe(recipeId: String, parameters: Map[String, Double] = Map.empty) extends EquipmentCommand

/** Abort the current job */
case class AbortJob(jobId: String, reason: String) extends EquipmentCommand

/** Query current equipment status */
case class QueryStatus() extends EquipmentCommand

/** Transfer a FOUP from one location to another (AMHS command) */
case class TransferFoup(foupId: String, fromPort: String, toPort: String) extends EquipmentCommand

// ============================================================================
// Equipment Event Model — events emitted BY equipment
// ============================================================================

sealed trait EquipmentEvent

/** Periodic or on-request status report from equipment */
case class StatusReport(
  equipmentId: String,
  status: EquipmentStatus,
  currentJob: Option[String],
  portOccupancy: Map[String, Option[String]] // portId → foupId
) extends EquipmentEvent

/** Job completed successfully, carrying equipment-specific result data */
case class JobCompleted(
  jobId: String,
  equipmentId: String,
  result: EquipmentResult
) extends EquipmentEvent

/** Job failed with error */
case class JobFailed(
  jobId: String,
  equipmentId: String,
  errorCode: String,
  detail: String
) extends EquipmentEvent

/** FOUP arrived at a port (emitted by AMHS / Stocker) */
case class FoupArrived(foupId: String, atPort: String) extends EquipmentEvent

/** FOUP departed from a port (emitted by AMHS / Stocker) */
case class FoupDeparted(foupId: String, fromPort: String) extends EquipmentEvent

// ============================================================================
// Equipment Status Enum
// ============================================================================

sealed trait EquipmentStatus
case object Idle extends EquipmentStatus
case object Busy extends EquipmentStatus
case object Error extends EquipmentStatus
case object Maintenance extends EquipmentStatus

// ============================================================================
// Equipment-Specific Result Data — structured measurement output
// ============================================================================

sealed trait EquipmentResult

/** Lithography exposure result — per-wafer process measurements */
case class LithoExposureResult(
  jobId: String,
  recipeId: String,
  wafers: Map[String, LithoMeasurement] // waferId → measurement
) extends EquipmentResult

/** Metrology (CD-SEM) measurement result — per-wafer critical dimensions */
case class MetrologyResult(
  jobId: String,
  wafers: Map[String, CriticalDimension] // waferId → measurement
) extends EquipmentResult

// ============================================================================
// Per-Wafer Measurement Value Objects
// ============================================================================

/** Lithography process measurement for a single wafer */
case class LithoMeasurement(
  waferId: String,
  alignmentErrorNm: Option[Double],  // None = no error measured
  resistThicknessNm: Option[Double],
  focusErrorNm: Option[Double]
)

/** Critical Dimension measurement for a single wafer */
case class CriticalDimension(
  waferId: String,
  measuredNm: Double,    // actual measured CD
  targetNm: Double = 32.0 // nominal target (for reference)
)

// ============================================================================
// Simulator Control
// ============================================================================

/** Controls simulation behavior (sent to simulators, not real equipment) */
sealed trait SimulatorControl

/** Adjust simulation speed multiplier */
case class AdjustSpeed(multiplier: Double) extends SimulatorControl

// ============================================================================
// Fault Injection (M3.5)
// ============================================================================

object FaultType {
  val HardwareFault = "HARDWARE_FAULT"
  val CommTimeout = "COMM_TIMEOUT"
  val SensorAnomaly = "SENSOR_ANOMALY"
  val PowerFluctuation = "POWER_FLUCTUATION"
  val all: Seq[String] = Seq(HardwareFault, CommTimeout, SensorAnomaly, PowerFluctuation)
}
