package net.imadz.domain.entities

import net.imadz.common.CommonTypes.Id
import net.imadz.common.CborSerializable

object WaferEntity {

  // @formatter:off
  // State
  case class WaferState(
    waferId: Id,
    lotId: Option[Id],                         // current owning lot
    status: WaferStatus,
    reservedTransfer: Option[(Id, Id)],        // (transferId, targetLotId) when transfer is in progress
    completedTransferIds: Set[Id] = Set.empty  // committed transfer ids for idempotency
  )

  def empty(waferId: Id): WaferState = WaferState(waferId, None, Created, None)

  // Status enum
  sealed trait WaferStatus extends CborSerializable
  case object Created extends WaferStatus
  case object Active extends WaferStatus
  case object OnHold extends WaferStatus
  case object Scrapped extends WaferStatus
  case object Skipped extends WaferStatus

  // Event
  sealed trait WaferEvent extends CborSerializable
  case class WaferCreated(lotId: Id) extends WaferEvent
  case class WaferAssigned(lotId: Id) extends WaferEvent
  case class WaferTransferReserved(transferId: Id, targetLotId: Id) extends WaferEvent
  case class WaferTransferCommitted(transferId: Id, targetLotId: Id) extends WaferEvent
  case class WaferTransferReleased(transferId: Id) extends WaferEvent
  case class WaferScrapped(reason: String) extends WaferEvent
  case class WaferStatusChanged(newStatus: WaferStatus) extends WaferEvent
  case class WaferHoldPlaced(reason: String) extends WaferEvent
  case class WaferHoldReleased() extends WaferEvent
  case class WaferSkipped(reason: String) extends WaferEvent
  // @formatter:on

  // Event Handler Extension Point
  type WaferEventHandler = (WaferState, WaferEvent) => WaferState

}
