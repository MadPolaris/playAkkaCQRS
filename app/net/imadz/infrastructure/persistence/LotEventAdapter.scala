package net.imadz.infrastructure.persistence

import akka.persistence.typed.{EventAdapter, EventSeq}
import net.imadz.domain.entities.LotEntity._
import net.imadz.infrastructure.persistence.converters.LotProtoConverters
import net.imadz.infrastructure.proto.lot._

class LotEventAdapter extends EventAdapter[LotEvent, LotEventPO.Event] with LotProtoConverters {

  override def manifest(event: LotEvent): String = event.getClass.getName

  override def toJournal(e: LotEvent): LotEventPO.Event = e match {
    case evt: LotCreated => LotEventPO.Event.LotCreated(LotCreatedConv.toProto(evt))
    case evt: WaferRemovalReserved => LotEventPO.Event.WaferRemovalReserved(WaferRemovalReservedConv.toProto(evt))
    case evt: WaferRemovalCommitted => LotEventPO.Event.WaferRemovalCommitted(WaferRemovalCommittedConv.toProto(evt))
    case evt: WaferRemovalReleased => LotEventPO.Event.WaferRemovalReleased(WaferRemovalReleasedConv.toProto(evt))
    case evt: WaferAdditionReserved => LotEventPO.Event.WaferAdditionReserved(WaferAdditionReservedConv.toProto(evt))
    case evt: WaferAdditionCommitted => LotEventPO.Event.WaferAdditionCommitted(WaferAdditionCommittedConv.toProto(evt))
    case evt: WaferAdditionCanceled => LotEventPO.Event.WaferAdditionCanceled(WaferAdditionCanceledConv.toProto(evt))
    case evt: PhaseStarted => LotEventPO.Event.PhaseStarted(PhaseStartedConv.toProto(evt))
    case evt: PhaseCompleted => LotEventPO.Event.PhaseCompleted(PhaseCompletedConv.toProto(evt))
    case evt: LotSealed => LotEventPO.Event.LotSealed(LotSealedConv.toProto(evt))
  }

  override def fromJournal(p: LotEventPO.Event, manifest: String): EventSeq[LotEvent] = p match {
    case LotEventPO.Event.LotCreated(po) => EventSeq.single(LotCreatedConv.fromProto(po))
    case LotEventPO.Event.WaferRemovalReserved(po) => EventSeq.single(WaferRemovalReservedConv.fromProto(po))
    case LotEventPO.Event.WaferRemovalCommitted(po) => EventSeq.single(WaferRemovalCommittedConv.fromProto(po))
    case LotEventPO.Event.WaferRemovalReleased(po) => EventSeq.single(WaferRemovalReleasedConv.fromProto(po))
    case LotEventPO.Event.WaferAdditionReserved(po) => EventSeq.single(WaferAdditionReservedConv.fromProto(po))
    case LotEventPO.Event.WaferAdditionCommitted(po) => EventSeq.single(WaferAdditionCommittedConv.fromProto(po))
    case LotEventPO.Event.WaferAdditionCanceled(po) => EventSeq.single(WaferAdditionCanceledConv.fromProto(po))
    case LotEventPO.Event.PhaseStarted(po) => EventSeq.single(PhaseStartedConv.fromProto(po))
    case LotEventPO.Event.PhaseCompleted(po) => EventSeq.single(PhaseCompletedConv.fromProto(po))
    case LotEventPO.Event.LotSealed(po) => EventSeq.single(LotSealedConv.fromProto(po))
    case LotEventPO.Event.Empty => EventSeq.empty
  }
}
