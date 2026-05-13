package net.imadz.infrastructure.persistence

import akka.persistence.typed.{EventAdapter, EventSeq}
import net.imadz.domain.entities.WaferEntity._
import net.imadz.infrastructure.persistence.converters.WaferProtoConverters
import net.imadz.infrastructure.proto.wafer._

class WaferEventAdapter extends EventAdapter[WaferEvent, WaferEventPO.Event] with WaferProtoConverters {

  override def manifest(event: WaferEvent): String = event.getClass.getName

  override def toJournal(e: WaferEvent): WaferEventPO.Event = e match {
    case evt: WaferCreated => WaferEventPO.Event.WaferCreated(WaferCreatedConv.toProto(evt))
    case evt: WaferAssigned => WaferEventPO.Event.WaferAssigned(WaferAssignedConv.toProto(evt))
    case evt: WaferTransferReserved => WaferEventPO.Event.WaferTransferReserved(WaferTransferReservedConv.toProto(evt))
    case evt: WaferTransferCommitted => WaferEventPO.Event.WaferTransferCommitted(WaferTransferCommittedConv.toProto(evt))
    case evt: WaferTransferReleased => WaferEventPO.Event.WaferTransferReleased(WaferTransferReleasedConv.toProto(evt))
    case evt: WaferScrapped => WaferEventPO.Event.WaferScrapped(WaferScrappedConv.toProto(evt))
    case evt: WaferStatusChanged => WaferEventPO.Event.WaferStatusChanged(WaferStatusChangedConv.toProto(evt))
    case evt: WaferHoldPlaced => WaferEventPO.Event.WaferHoldPlaced(WaferHoldPlacedConv.toProto(evt))
    case evt: WaferHoldReleased => WaferEventPO.Event.WaferHoldReleased(WaferHoldReleasedConv.toProto(evt))
    case evt: WaferSkipped => WaferEventPO.Event.WaferSkipped(WaferSkippedConv.toProto(evt))
  }

  override def fromJournal(p: WaferEventPO.Event, manifest: String): EventSeq[WaferEvent] = p match {
    case WaferEventPO.Event.WaferCreated(po) => EventSeq.single(WaferCreatedConv.fromProto(po))
    case WaferEventPO.Event.WaferAssigned(po) => EventSeq.single(WaferAssignedConv.fromProto(po))
    case WaferEventPO.Event.WaferTransferReserved(po) => EventSeq.single(WaferTransferReservedConv.fromProto(po))
    case WaferEventPO.Event.WaferTransferCommitted(po) => EventSeq.single(WaferTransferCommittedConv.fromProto(po))
    case WaferEventPO.Event.WaferTransferReleased(po) => EventSeq.single(WaferTransferReleasedConv.fromProto(po))
    case WaferEventPO.Event.WaferScrapped(po) => EventSeq.single(WaferScrappedConv.fromProto(po))
    case WaferEventPO.Event.WaferStatusChanged(po) => EventSeq.single(WaferStatusChangedConv.fromProto(po))
    case WaferEventPO.Event.WaferHoldPlaced(po) => EventSeq.single(WaferHoldPlacedConv.fromProto(po))
    case WaferEventPO.Event.WaferHoldReleased(po) => EventSeq.single(WaferHoldReleasedConv.fromProto(po))
    case WaferEventPO.Event.WaferSkipped(po) => EventSeq.single(WaferSkippedConv.fromProto(po))
    case WaferEventPO.Event.Empty => EventSeq.empty
  }
}
