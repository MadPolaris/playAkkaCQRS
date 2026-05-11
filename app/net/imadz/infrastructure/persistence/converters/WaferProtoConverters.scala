package net.imadz.infrastructure.persistence.converters

import net.imadz.common.serialization.PrimitiveConverter
import net.imadz.domain.entities.WaferEntity._
import net.imadz.infrastructure.proto.wafer._

trait WaferProtoConverters extends PrimitiveConverter {

  // --- Events ---

  object WaferCreatedConv extends ProtoConverter[WaferCreated, WaferCreatedPO] {
    override def toProto(e: WaferCreated): WaferCreatedPO = WaferCreatedPO(lotId = IdConv.toProto(e.lotId))
    override def fromProto(p: WaferCreatedPO): WaferCreated = WaferCreated(IdConv.fromProto(p.lotId))
  }

  object WaferAssignedConv extends ProtoConverter[WaferAssigned, WaferAssignedPO] {
    override def toProto(e: WaferAssigned): WaferAssignedPO = WaferAssignedPO(lotId = IdConv.toProto(e.lotId))
    override def fromProto(p: WaferAssignedPO): WaferAssigned = WaferAssigned(IdConv.fromProto(p.lotId))
  }

  object WaferTransferReservedConv extends ProtoConverter[WaferTransferReserved, WaferTransferReservedPO] {
    override def toProto(e: WaferTransferReserved): WaferTransferReservedPO = WaferTransferReservedPO(
      transferId = IdConv.toProto(e.transferId),
      targetLotId = IdConv.toProto(e.targetLotId)
    )
    override def fromProto(p: WaferTransferReservedPO): WaferTransferReserved = WaferTransferReserved(
      transferId = IdConv.fromProto(p.transferId),
      targetLotId = IdConv.fromProto(p.targetLotId)
    )
  }

  object WaferTransferCommittedConv extends ProtoConverter[WaferTransferCommitted, WaferTransferCommittedPO] {
    override def toProto(e: WaferTransferCommitted): WaferTransferCommittedPO = WaferTransferCommittedPO(
      transferId = IdConv.toProto(e.transferId),
      targetLotId = IdConv.toProto(e.targetLotId)
    )
    override def fromProto(p: WaferTransferCommittedPO): WaferTransferCommitted = WaferTransferCommitted(
      transferId = IdConv.fromProto(p.transferId),
      targetLotId = IdConv.fromProto(p.targetLotId)
    )
  }

  object WaferTransferReleasedConv extends ProtoConverter[WaferTransferReleased, WaferTransferReleasedPO] {
    override def toProto(e: WaferTransferReleased): WaferTransferReleasedPO = WaferTransferReleasedPO(
      transferId = IdConv.toProto(e.transferId)
    )
    override def fromProto(p: WaferTransferReleasedPO): WaferTransferReleased = WaferTransferReleased(
      transferId = IdConv.fromProto(p.transferId)
    )
  }

  object WaferScrappedConv extends ProtoConverter[WaferScrapped, WaferScrappedPO] {
    override def toProto(e: WaferScrapped): WaferScrappedPO = WaferScrappedPO(reason = e.reason)
    override def fromProto(p: WaferScrappedPO): WaferScrapped = WaferScrapped(p.reason)
  }

  object WaferStatusChangedConv extends ProtoConverter[WaferStatusChanged, WaferStatusChangedPO] {
    override def toProto(e: WaferStatusChanged): WaferStatusChangedPO = WaferStatusChangedPO(
      newStatus = e.newStatus.toString
    )
    override def fromProto(p: WaferStatusChangedPO): WaferStatusChanged = WaferStatusChanged(parseStatus(p.newStatus))
    private def parseStatus(s: String): WaferStatus = s match {
      case "Created" => Created; case "Active" => Active; case "OnHold" => OnHold
      case "Scrapped" => Scrapped; case "Skipped" => Skipped; case _ => Created
    }
  }

  // --- State Snapshot ---

  object WaferStateConv extends ProtoConverter[WaferState, WaferStatePO] {
    override def toProto(s: WaferState): WaferStatePO = {
      val (reservedTid, reservedTgt) = s.reservedTransfer match {
        case Some((tid, tgt)) => (Some(IdConv.toProto(tid)), Some(IdConv.toProto(tgt)))
        case None => (None, None)
      }
      WaferStatePO(
        waferId = IdConv.toProto(s.waferId),
        lotId = s.lotId.map(IdConv.toProto),
        status = s.status.toString,
        reservedTransferId = reservedTid,
        reservedTargetLotId = reservedTgt
      )
    }

    override def fromProto(p: WaferStatePO): WaferState = {
      val reserved = (p.reservedTransferId, p.reservedTargetLotId) match {
        case (Some(tid), Some(tgt)) => Some((IdConv.fromProto(tid), IdConv.fromProto(tgt)))
        case _ => None
      }
      WaferState(
        waferId = IdConv.fromProto(p.waferId),
        lotId = p.lotId.map(IdConv.fromProto),
        status = parseStatus(p.status),
        reservedTransfer = reserved
      )
    }

    private def parseStatus(s: String): WaferStatus = s match {
      case "Created" => Created; case "Active" => Active; case "OnHold" => OnHold
      case "Scrapped" => Scrapped; case "Skipped" => Skipped; case _ => Created
    }
  }
}
