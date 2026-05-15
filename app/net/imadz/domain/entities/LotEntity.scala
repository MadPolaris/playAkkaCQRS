package net.imadz.domain.entities

import net.imadz.common.CommonTypes.Id
import net.imadz.common.CborSerializable

object LotEntity {

  // @formatter:off
  // State
  case class LotState(
    lotId: Id,
    productId: String,
    waferIds: Set[Id],                  // currently owned wafers
    reservedWafers: Map[Id, Set[Id]],   // outgoing: transferId -> waferIds being removed
    incomingWafers: Map[Id, Set[Id]],   // incoming: transferId -> waferIds being added
    phase: LotPhase,
    completedTransferIds: Set[Id] = Set.empty, // committed transfer ids for idempotency
    // Process execution tracking
    currentStepIndex: Int = 0,
    areaVisitHistory: List[String] = Nil,
    routingStepReentry: Map[String, Int] = Map.empty,
    // Idempotency guards for equipment reports
    loadedFoupId: Option[String] = None,
    completedJobs: Set[String] = Set.empty,
    measuredWafers: Set[String] = Set.empty,
    waferClassifications: Map[String, WaferClassResult] = Map.empty
  )

  def empty(lotId: Id): LotState = LotState(lotId, "", Set.empty, Map.empty, Map.empty, Empty)

  case class WaferClassResult(
    classification: String,
    cdValueNm: Double,
    reworkCount: Int
  )

  // Phase state machine
  sealed trait LotPhase extends CborSerializable
  case object Empty extends LotPhase
  case object Active extends LotPhase
  case object Sealed extends LotPhase
  case object Completed extends LotPhase

  // Event
  sealed trait LotEvent extends CborSerializable
  case class LotCreated(productId: String, waferIds: Set[Id]) extends LotEvent
  case class WaferRemovalReserved(transferId: Id, waferIds: Set[Id]) extends LotEvent
  case class WaferRemovalCommitted(transferId: Id) extends LotEvent
  case class WaferRemovalReleased(transferId: Id) extends LotEvent
  case class WaferAdditionReserved(transferId: Id, waferIds: Set[Id]) extends LotEvent
  case class WaferAdditionCommitted(transferId: Id) extends LotEvent
  case class WaferAdditionCanceled(transferId: Id) extends LotEvent
  case class PhaseStarted(phaseId: String) extends LotEvent
  case class PhaseCompleted(phaseId: String) extends LotEvent
  case class LotSealed() extends LotEvent
  // Process execution events (equipment reports routed to Lot)
  case class FoupLoaded(foupId: String, stockerId: String) extends LotEvent
  case class TransportStarted(foupId: String, fromArea: String, toArea: String, estimatedMs: Long) extends LotEvent
  case class TransportCompleted(foupId: String, equipmentId: String) extends LotEvent
  case class EquipmentJobStarted(equipmentId: String, recipeId: String) extends LotEvent
  case class EquipmentJobCompleted(equipmentId: String, jobId: String, success: Boolean) extends LotEvent
  case class WaferMeasured(waferId: String, cdNm: Double) extends LotEvent
  case class WaferClassified(waferId: String, classification: String, reworkCount: Int, cdValue: Double) extends LotEvent
  case class WafersSplitForRework(reworkWaferIds: Set[String], scrapWaferIds: Set[String], iteration: Int) extends LotEvent
  case class WafersReworked(waferIds: Set[String]) extends LotEvent
  case class WafersSentAsPilot(waferIds: Set[String]) extends LotEvent
  case class WafersSampled(sampleIds: Set[String], skipIds: Set[String]) extends LotEvent
  case class WafersHeld(waferIds: Set[String], reason: String) extends LotEvent
  case class WafersReleased(waferIds: Set[String]) extends LotEvent
  case class ProcessCompleted(lotId: String, passCount: Int, scrapCount: Int, reworkCount: Int) extends LotEvent
  // @formatter:on

  // Event Handler Extension Point
  type LotEventHandler = (LotState, LotEvent) => LotState

}
