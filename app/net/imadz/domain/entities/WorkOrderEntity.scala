package net.imadz.domain.entities

import net.imadz.common.CborSerializable

object WorkOrderEntity {

  // @formatter:off
  // State
  sealed trait WorkOrderState extends CborSerializable
  case object Idle extends WorkOrderState
  case class Executing(
    workOrderId: String,
    productId: String,
    waferIds: Seq[String],
    sourceLotId: Option[String] = None,
    reworkLotId: Option[String] = None
  ) extends WorkOrderState
  case class Completed(
    passCount: Int,
    scrapCount: Int,
    reworkCount: Int
  ) extends WorkOrderState
  case class Failed(error: String) extends WorkOrderState

  def empty: WorkOrderState = Idle

  // Event
  sealed trait WorkOrderEvent extends CborSerializable
  case class WorkOrderCreated(
    workOrderId: String,
    productId: String,
    waferIds: Seq[String],
    waferCount: Int
  ) extends WorkOrderEvent
  case class WorkOrderCompleted(
    passCount: Int,
    scrapCount: Int,
    reworkCount: Int
  ) extends WorkOrderEvent
  case class WorkOrderFailed(error: String) extends WorkOrderEvent

  // Event Handler
  type WorkOrderEventHandler = (WorkOrderState, WorkOrderEvent) => WorkOrderState

  def handleEvent: WorkOrderEventHandler = (state, event) => event match {
    case WorkOrderCreated(workOrderId, productId, waferIds, _) =>
      Executing(workOrderId, productId, waferIds)

    case WorkOrderCompleted(passCount, scrapCount, reworkCount) =>
      Completed(passCount, scrapCount, reworkCount)

    case WorkOrderFailed(error) =>
      Failed(error)
  }
  // @formatter:on
}
