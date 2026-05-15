package net.imadz.infrastructure.persistence

import akka.persistence.typed.{EventAdapter, EventSeq}
import net.imadz.domain.entities.WorkOrderEntity._
import net.imadz.infrastructure.persistence.converters.WorkOrderProtoConverters
import net.imadz.infrastructure.proto.work_order._

class WorkOrderEventAdapter extends EventAdapter[WorkOrderEvent, WorkOrderEventPO] with WorkOrderProtoConverters {

  override def manifest(event: WorkOrderEvent): String = event.getClass.getName

  override def toJournal(e: WorkOrderEvent): WorkOrderEventPO = e match {
    case evt: WorkOrderCreated   => WorkOrderEventPO(WorkOrderEventPO.Event.WorkOrderCreated(WorkOrderCreatedConv.toProto(evt)))
    case evt: WorkOrderCompleted => WorkOrderEventPO(WorkOrderEventPO.Event.WorkOrderCompleted(WorkOrderCompletedConv.toProto(evt)))
    case evt: WorkOrderFailed    => WorkOrderEventPO(WorkOrderEventPO.Event.WorkOrderFailed(WorkOrderFailedConv.toProto(evt)))
  }

  override def fromJournal(p: WorkOrderEventPO, manifest: String): EventSeq[WorkOrderEvent] = p.event match {
    case WorkOrderEventPO.Event.WorkOrderCreated(po)   => EventSeq.single(WorkOrderCreatedConv.fromProto(po))
    case WorkOrderEventPO.Event.WorkOrderCompleted(po) => EventSeq.single(WorkOrderCompletedConv.fromProto(po))
    case WorkOrderEventPO.Event.WorkOrderFailed(po)    => EventSeq.single(WorkOrderFailedConv.fromProto(po))
    case WorkOrderEventPO.Event.Empty                  => EventSeq.empty
  }
}
