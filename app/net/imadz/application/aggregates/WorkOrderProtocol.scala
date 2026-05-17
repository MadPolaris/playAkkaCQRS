package net.imadz.application.aggregates

import akka.actor.typed.ActorRef
import akka.persistence.typed.scaladsl.Effect
import net.imadz.common.CborSerializable
import net.imadz.domain.entities.WorkOrderEntity.{WorkOrderEvent, WorkOrderState}

import scala.concurrent.Future

object WorkOrderProtocol {

  // --- Pipeline Runner (closure provided by FabDemoService) ---
  // publisher: callback for WebSocket events (no-op during recovery)
  type PipelineStarter = (String, String, Seq[String], Any => Unit) => Future[(Int, Int, Int)]

  // --- Commands ---
  sealed trait WorkOrderCommand extends CborSerializable

  case class CreateWorkOrder(
    productId: String,
    waferIds: Seq[String],
    routeRef: Option[String] = None,  // M3.5+: "routeId:v3"
    replyTo: ActorRef[WorkOrderConfirmation]
  ) extends WorkOrderCommand

  // Internal commands (from pipeToSelf)
  private[aggregates] case class PipelineCompleted(
    passCount: Int,
    scrapCount: Int,
    reworkCount: Int
  ) extends WorkOrderCommand

  private[aggregates] case class PipelineFailed(
    error: String
  ) extends WorkOrderCommand

  // --- Reply ---
  case class WorkOrderConfirmation(workOrderId: String, phase: String) extends CborSerializable

  // --- Handler Type ---
  type WorkOrderCommandHandler = (WorkOrderState, WorkOrderCommand) => Effect[WorkOrderEvent, WorkOrderState]
}
