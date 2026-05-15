package net.imadz.domain.entities

import net.imadz.common.CborSerializable

object FabProcessEntity {

  // @formatter:off
  // State
  case class FabProcessState(
    processId: String,
    lotId: String,
    waferIds: Set[String],
    lotSize: Int,
    passCount: Int,
    scrapCount: Int,
    reworkCount: Int,
    phase: ProcessPhase,
    waferClassifications: Map[String, WaferClassResult]
  )

  case class WaferClassResult(
    classification: String,
    cdValueNm: Double,
    reworkCount: Int
  )

  def empty(processId: String): FabProcessState =
    FabProcessState(processId, "", Set.empty, 0, 0, 0, 0, ProcessCreated, Map.empty)


  sealed trait ProcessPhase extends CborSerializable
  case object ProcessCreated extends ProcessPhase
  case object ProcessActive extends ProcessPhase
  case object ProcessCompleted extends ProcessPhase

  // Event
  sealed trait FabProcessEvent extends CborSerializable
  case class ProcessStarted(lotId: String, waferIds: Set[String], lotSize: Int) extends FabProcessEvent
  case class FoupLoaded(foupId: String, stockerId: String) extends FabProcessEvent
  case class TransportStarted(foupId: String, fromArea: String, toArea: String, estimatedMs: Long) extends FabProcessEvent
  case class TransportCompleted(foupId: String, equipmentId: String) extends FabProcessEvent
  case class EquipmentJobStarted(equipmentId: String, recipeId: String) extends FabProcessEvent
  case class EquipmentJobCompleted(equipmentId: String, jobId: String, success: Boolean) extends FabProcessEvent
  case class WaferMeasured(waferId: String, cdNm: Double) extends FabProcessEvent
  case class WaferClassified(waferId: String, classification: String, reworkCount: Int, cdValue: Double) extends FabProcessEvent
  case class WafersSplitForRework(reworkWaferIds: Set[String], scrapWaferIds: Set[String], iteration: Int) extends FabProcessEvent
  case class WafersReworked(waferIds: Set[String]) extends FabProcessEvent
  case class WafersSentAsPilot(waferIds: Set[String]) extends FabProcessEvent
  case class WafersSampled(sampleIds: Set[String], skipIds: Set[String]) extends FabProcessEvent
  case class WafersHeld(waferIds: Set[String], reason: String) extends FabProcessEvent
  case class WafersReleased(waferIds: Set[String]) extends FabProcessEvent
  case class ProcessCompleted(lotId: String, passCount: Int, scrapCount: Int, reworkCount: Int) extends FabProcessEvent
  // @formatter:on

  // Event Handler Extension Point
  type ProcessEventHandler = (FabProcessState, FabProcessEvent) => FabProcessState

  // EventStream envelope (carries processId for bridge routing)
  case class ProcessEventEnvelope(processId: String, event: FabProcessEvent) extends CborSerializable

}
