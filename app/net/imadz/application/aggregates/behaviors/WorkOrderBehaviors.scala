package net.imadz.application.aggregates.behaviors

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import net.imadz.application.aggregates.WorkOrderProtocol._
import net.imadz.domain.entities.WorkOrderEntity.{WorkOrderState, _}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object WorkOrderBehaviors {

  def apply(
    workOrderId: String,
    pipelineStarter: PipelineStarter,
    actorContext: ActorContext[WorkOrderCommand]
  ): WorkOrderCommandHandler = {
    implicit val ec: ExecutionContext = actorContext.executionContext

    (state, command) => command match {

      case cmd: CreateWorkOrder =>
        if (state != Idle)
          Effect.reply(cmd.replyTo)(WorkOrderConfirmation(workOrderId, "AlreadyActive"))
        else
          Effect.persist(WorkOrderCreated(workOrderId, cmd.productId, cmd.waferIds, cmd.waferIds.size, routeRef = cmd.routeRef))
            .thenRun { _ =>
              cmd.replyTo ! WorkOrderConfirmation(workOrderId, "Executing")
              actorContext.pipeToSelf(
                pipelineStarter(workOrderId, cmd.productId, cmd.waferIds, _ => ())
              ) {
                case Success((pass, scrap, rework)) =>
                  PipelineCompleted(pass, scrap, rework)
                case Failure(err) =>
                  PipelineFailed(err.getMessage)
              }
            }

      case PipelineCompleted(passCount, scrapCount, reworkCount) =>
        Effect.persist(WorkOrderCompleted(passCount, scrapCount, reworkCount))

      case PipelineFailed(error) =>
        Effect.persist(WorkOrderFailed(error))
    }
  }
}
