package net.imadz.infrastructure.persistence.converters

import net.imadz.common.serialization.PrimitiveConverter
import net.imadz.domain.entities.WorkOrderEntity._
import net.imadz.infrastructure.proto.work_order._

trait WorkOrderProtoConverters extends PrimitiveConverter {

  object WorkOrderCreatedConv extends ProtoConverter[WorkOrderCreated, WorkOrderCreatedPO] {
    override def toProto(e: WorkOrderCreated): WorkOrderCreatedPO =
      WorkOrderCreatedPO(
        workOrderId = e.workOrderId,
        productId = e.productId,
        waferIds = e.waferIds,
        waferCount = e.waferCount
      )
    override def fromProto(p: WorkOrderCreatedPO): WorkOrderCreated =
      WorkOrderCreated(
        workOrderId = p.workOrderId,
        productId = p.productId,
        waferIds = p.waferIds,
        waferCount = p.waferCount
      )
  }

  object WorkOrderCompletedConv extends ProtoConverter[WorkOrderCompleted, WorkOrderCompletedPO] {
    override def toProto(e: WorkOrderCompleted): WorkOrderCompletedPO =
      WorkOrderCompletedPO(passCount = e.passCount, scrapCount = e.scrapCount, reworkCount = e.reworkCount)
    override def fromProto(p: WorkOrderCompletedPO): WorkOrderCompleted =
      WorkOrderCompleted(passCount = p.passCount, scrapCount = p.scrapCount, reworkCount = p.reworkCount)
  }

  object WorkOrderFailedConv extends ProtoConverter[WorkOrderFailed, WorkOrderFailedPO] {
    override def toProto(e: WorkOrderFailed): WorkOrderFailedPO =
      WorkOrderFailedPO(error = e.error)
    override def fromProto(p: WorkOrderFailedPO): WorkOrderFailed =
      WorkOrderFailed(error = p.error)
  }

  // --- State Snapshot Converter ---

  object WorkOrderStateConv extends ProtoConverter[WorkOrderState, WorkOrderStatePO] {
    override def toProto(s: WorkOrderState): WorkOrderStatePO = s match {
      case Idle =>
        WorkOrderStatePO(phase = "Idle")
      case Executing(workOrderId, productId, waferIds, _, _) =>
        WorkOrderStatePO(phase = "Executing", workOrderId = workOrderId, productId = productId, waferIds = waferIds)
      case Completed(passCount, scrapCount, reworkCount) =>
        WorkOrderStatePO(phase = "Completed", passCount = passCount, scrapCount = scrapCount, reworkCount = reworkCount)
      case Failed(error) =>
        WorkOrderStatePO(phase = "Failed", error = error)
    }
    override def fromProto(p: WorkOrderStatePO): WorkOrderState = p.phase match {
      case "Idle"       => Idle
      case "Executing"  => Executing(p.workOrderId, p.productId, p.waferIds.toSeq)
      case "Completed"  => Completed(p.passCount, p.scrapCount, p.reworkCount)
      case "Failed"     => Failed(p.error)
      case other        => throw new IllegalArgumentException(s"Unknown WorkOrder phase: $other")
    }
  }
}
