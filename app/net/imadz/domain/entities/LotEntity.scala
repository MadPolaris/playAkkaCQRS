package net.imadz.domain.entities

import net.imadz.common.CommonTypes.Id
import net.imadz.common.CborSerializable

object LotEntity {

  // @formatter:off
  // --- Wafer status (from deleted WaferEntity, now in-process within Lot) ---
  sealed trait WaferStatus extends CborSerializable
  case object WaferActive extends WaferStatus
  case object WaferOnHold extends WaferStatus
  case object WaferScrapped extends WaferStatus
  case object WaferSkipped extends WaferStatus

  // --- Wafer intrinsic state (single source of truth) ---
  case class WaferState(
    name: String,
    status: WaferStatus = WaferActive,
    classification: Option[String] = None, // PASS/FAIL — None before first classify
    reworkCount: Int = 0,
    cdValue: Option[Double] = None,       // latest CD measurement
    measured: Boolean = false
  ) {
    def isScrapped: Boolean = status == WaferScrapped
    def isActive: Boolean = status == WaferActive
  }

  // --- Lot aggregate state ---
  case class LotState(
    lotId: Id,
    productId: String,
    wafers: Map[Id, WaferState] = Map.empty, // UUID → wafer — single source for all wafer attributes
    // Saga TCC reservation state (distributed transaction bookkeeping, not wafer attributes)
    reservedWafers: Map[Id, Set[Id]] = Map.empty,
    reservedWaferNames: Map[Id, Set[String]] = Map.empty,
    incomingWafers: Map[Id, Set[Id]] = Map.empty,
    phase: LotPhase = Empty,
    completedTransferIds: Set[Id] = Set.empty,
    loadedFoupId: Option[String] = None
  ) {
    // --- Derived views (computed on-demand, no cache) ---
    def waferIds: Set[Id] = wafers.keySet
    def measuredWafers: Set[Id] = wafers.collect { case (id, ws) if ws.measured => id }.toSet
    def waferClassifications: Map[Id, WaferClassResult] =
      wafers.collect { case (id, ws) if ws.classification.isDefined =>
        id -> WaferClassResult(ws.classification.get, ws.cdValue.getOrElse(0.0), ws.reworkCount)
      }
    def waferNameById(id: Id): Option[String] = wafers.get(id).map(_.name)
  }

  def empty(lotId: Id): LotState = LotState(lotId = lotId, productId = "")

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
  case class LotCreated(productId: String, waferNames: Map[Id, String]) extends LotEvent
  case class WaferRemovalReserved(transferId: Id, waferIds: Set[Id], waferNames: Set[String]) extends LotEvent
  case class WaferRemovalCommitted(transferId: Id, waferNames: Set[String]) extends LotEvent
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
  case class WaferMeasured(waferId: Id, cdNm: Double) extends LotEvent
  case class WaferClassified(waferId: Id, classification: String, reworkCount: Int, cdValue: Double) extends LotEvent
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
