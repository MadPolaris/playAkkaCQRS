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
    case evt: FoupLoaded => LotEventPO.Event.FoupLoaded(FoupLoadedConv.toProto(evt))
    case evt: TransportStarted => LotEventPO.Event.TransportStarted(TransportStartedConv.toProto(evt))
    case evt: TransportCompleted => LotEventPO.Event.TransportCompleted(TransportCompletedConv.toProto(evt))
    case evt: EquipmentJobStarted => LotEventPO.Event.EquipmentJobStarted(EquipmentJobStartedConv.toProto(evt))
    case evt: EquipmentJobCompleted => LotEventPO.Event.EquipmentJobCompleted(EquipmentJobCompletedConv.toProto(evt))
    case evt: WaferMeasured => LotEventPO.Event.WaferMeasured(WaferMeasuredConv.toProto(evt))
    case evt: WaferClassified => LotEventPO.Event.WaferClassified(WaferClassifiedConv.toProto(evt))
    case evt: WafersSplitForRework => LotEventPO.Event.WafersSplitForRework(WafersSplitForReworkConv.toProto(evt))
    case evt: WafersReworked => LotEventPO.Event.WafersReworked(WafersReworkedConv.toProto(evt))
    case evt: WafersSentAsPilot => LotEventPO.Event.WafersSentAsPilot(WafersSentAsPilotConv.toProto(evt))
    case evt: WafersSampled => LotEventPO.Event.WafersSampled(WafersSampledConv.toProto(evt))
    case evt: WafersHeld => LotEventPO.Event.WafersHeld(WafersHeldConv.toProto(evt))
    case evt: WafersReleased => LotEventPO.Event.WafersReleased(WafersReleasedConv.toProto(evt))
    case evt: ProcessCompleted => LotEventPO.Event.ProcessCompleted(ProcessCompletedConv.toProto(evt))
    case evt: LotFailed => LotEventPO.Event.LotFailed(LotFailedConv.toProto(evt))
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
    case LotEventPO.Event.FoupLoaded(po) => EventSeq.single(FoupLoadedConv.fromProto(po))
    case LotEventPO.Event.TransportStarted(po) => EventSeq.single(TransportStartedConv.fromProto(po))
    case LotEventPO.Event.TransportCompleted(po) => EventSeq.single(TransportCompletedConv.fromProto(po))
    case LotEventPO.Event.EquipmentJobStarted(po) => EventSeq.single(EquipmentJobStartedConv.fromProto(po))
    case LotEventPO.Event.EquipmentJobCompleted(po) => EventSeq.single(EquipmentJobCompletedConv.fromProto(po))
    case LotEventPO.Event.WaferMeasured(po) => EventSeq.single(WaferMeasuredConv.fromProto(po))
    case LotEventPO.Event.WaferClassified(po) => EventSeq.single(WaferClassifiedConv.fromProto(po))
    case LotEventPO.Event.WafersSplitForRework(po) => EventSeq.single(WafersSplitForReworkConv.fromProto(po))
    case LotEventPO.Event.WafersReworked(po) => EventSeq.single(WafersReworkedConv.fromProto(po))
    case LotEventPO.Event.WafersSentAsPilot(po) => EventSeq.single(WafersSentAsPilotConv.fromProto(po))
    case LotEventPO.Event.WafersSampled(po) => EventSeq.single(WafersSampledConv.fromProto(po))
    case LotEventPO.Event.WafersHeld(po) => EventSeq.single(WafersHeldConv.fromProto(po))
    case LotEventPO.Event.WafersReleased(po) => EventSeq.single(WafersReleasedConv.fromProto(po))
    case LotEventPO.Event.ProcessCompleted(po) => EventSeq.single(ProcessCompletedConv.fromProto(po))
    case LotEventPO.Event.LotFailed(po) => EventSeq.single(LotFailedConv.fromProto(po))
    case LotEventPO.Event.Empty => EventSeq.empty
  }
}
