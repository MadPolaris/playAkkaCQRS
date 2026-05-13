package net.imadz.infrastructure.persistence

import akka.persistence.typed.{EventAdapter, EventSeq}
import net.imadz.domain.entities.FabProcessEntity._
import net.imadz.infrastructure.persistence.converters.ProcessProtoConverters
import net.imadz.infrastructure.proto.process._

class ProcessEventAdapter extends EventAdapter[FabProcessEvent, ProcessEventPO.Event] with ProcessProtoConverters {

  override def manifest(event: FabProcessEvent): String = event.getClass.getName

  override def toJournal(e: FabProcessEvent): ProcessEventPO.Event = e match {
    case evt: ProcessStarted         => ProcessEventPO.Event.ProcessStarted(ProcessStartedConv.toProto(evt))
    case evt: FoupLoaded             => ProcessEventPO.Event.FoupLoaded(FoupLoadedConv.toProto(evt))
    case evt: TransportStarted       => ProcessEventPO.Event.TransportStarted(TransportStartedConv.toProto(evt))
    case evt: TransportCompleted     => ProcessEventPO.Event.TransportCompleted(TransportCompletedConv.toProto(evt))
    case evt: EquipmentJobStarted    => ProcessEventPO.Event.EquipmentJobStarted(EquipmentJobStartedConv.toProto(evt))
    case evt: EquipmentJobCompleted  => ProcessEventPO.Event.EquipmentJobCompleted(EquipmentJobCompletedConv.toProto(evt))
    case evt: WaferMeasured          => ProcessEventPO.Event.WaferMeasured(WaferMeasuredConv.toProto(evt))
    case evt: WaferClassified        => ProcessEventPO.Event.WaferClassified(WaferClassifiedConv.toProto(evt))
    case evt: WafersSplitForRework   => ProcessEventPO.Event.WafersSplitForRework(WafersSplitForReworkConv.toProto(evt))
    case evt: WafersReworked         => ProcessEventPO.Event.WafersReworked(WafersReworkedConv.toProto(evt))
    case evt: WafersSentAsPilot      => ProcessEventPO.Event.WafersSentAsPilot(WafersSentAsPilotConv.toProto(evt))
    case evt: WafersSampled          => ProcessEventPO.Event.WafersSampled(WafersSampledConv.toProto(evt))
    case evt: WafersHeld             => ProcessEventPO.Event.WafersHeld(WafersHeldConv.toProto(evt))
    case evt: WafersReleased         => ProcessEventPO.Event.WafersReleased(WafersReleasedConv.toProto(evt))
    case evt: ProcessCompleted       => ProcessEventPO.Event.ProcessCompleted(ProcessCompletedConv.toProto(evt))
  }

  override def fromJournal(p: ProcessEventPO.Event, manifest: String): EventSeq[FabProcessEvent] = p match {
    case ProcessEventPO.Event.ProcessStarted(po)         => EventSeq.single(ProcessStartedConv.fromProto(po))
    case ProcessEventPO.Event.FoupLoaded(po)             => EventSeq.single(FoupLoadedConv.fromProto(po))
    case ProcessEventPO.Event.TransportStarted(po)       => EventSeq.single(TransportStartedConv.fromProto(po))
    case ProcessEventPO.Event.TransportCompleted(po)     => EventSeq.single(TransportCompletedConv.fromProto(po))
    case ProcessEventPO.Event.EquipmentJobStarted(po)    => EventSeq.single(EquipmentJobStartedConv.fromProto(po))
    case ProcessEventPO.Event.EquipmentJobCompleted(po)  => EventSeq.single(EquipmentJobCompletedConv.fromProto(po))
    case ProcessEventPO.Event.WaferMeasured(po)          => EventSeq.single(WaferMeasuredConv.fromProto(po))
    case ProcessEventPO.Event.WaferClassified(po)        => EventSeq.single(WaferClassifiedConv.fromProto(po))
    case ProcessEventPO.Event.WafersSplitForRework(po)   => EventSeq.single(WafersSplitForReworkConv.fromProto(po))
    case ProcessEventPO.Event.WafersReworked(po)         => EventSeq.single(WafersReworkedConv.fromProto(po))
    case ProcessEventPO.Event.WafersSentAsPilot(po)      => EventSeq.single(WafersSentAsPilotConv.fromProto(po))
    case ProcessEventPO.Event.WafersSampled(po)          => EventSeq.single(WafersSampledConv.fromProto(po))
    case ProcessEventPO.Event.WafersHeld(po)             => EventSeq.single(WafersHeldConv.fromProto(po))
    case ProcessEventPO.Event.WafersReleased(po)         => EventSeq.single(WafersReleasedConv.fromProto(po))
    case ProcessEventPO.Event.ProcessCompleted(po)       => EventSeq.single(ProcessCompletedConv.fromProto(po))
    case ProcessEventPO.Event.Empty                      => EventSeq.empty
  }
}
