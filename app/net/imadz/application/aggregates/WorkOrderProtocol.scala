package net.imadz.application.aggregates

import akka.actor.typed.ActorRef
import akka.persistence.typed.scaladsl.Effect
import net.imadz.common.CborSerializable
import net.imadz.domain.entities.WorkOrderEntity.{WorkOrderEvent, WorkOrderState}

object WorkOrderProtocol {

  // --- Commands ---
  sealed trait WorkOrderCommand extends CborSerializable

  case class CreateWorkOrder(
    productId: String,
    waferIds: Seq[String],
    routeRef: Option[String] = None,  // M3.5+: "routeId:v3"
    totalLots: Int = 1,               // number of source lots to track
    replyTo: ActorRef[WorkOrderConfirmation]
  ) extends WorkOrderCommand

  // Event-driven completion report (from WorkOrderCompletionProcessManager)
  case class RecordLotCompleted(
    workOrderId: String,
    lotId: String,
    passCount: Int,
    scrapCount: Int,
    reworkCount: Int
  ) extends WorkOrderCommand

  case class RecordLotFailed(
    workOrderId: String,
    lotId: String,
    reason: String,
    failedAt: String
  ) extends WorkOrderCommand

  // --- Reply ---
  case class WorkOrderConfirmation(workOrderId: String, phase: String) extends CborSerializable

  // --- Handler Type ---
  type WorkOrderCommandHandler = (WorkOrderState, WorkOrderCommand) => Effect[WorkOrderEvent, WorkOrderState]
}
