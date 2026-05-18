package net.imadz.application.aggregates.behaviors

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import net.imadz.application.aggregates.WorkOrderProtocol._
import net.imadz.domain.entities.WorkOrderEntity.{WorkOrderState, _}

object WorkOrderBehaviors {

  def apply(
    workOrderId: String,
    actorContext: ActorContext[WorkOrderCommand]
  ): WorkOrderCommandHandler = {

    (state, command) => command match {

      case cmd: CreateWorkOrder =>
        if (state != Idle)
          Effect.reply(cmd.replyTo)(WorkOrderConfirmation(workOrderId, "AlreadyActive"))
        else
          Effect.persist(WorkOrderCreated(workOrderId, cmd.productId, cmd.waferIds, cmd.waferIds.size,
            routeRef = cmd.routeRef, totalLots = cmd.totalLots))
            .thenReply(cmd.replyTo)(_ => WorkOrderConfirmation(workOrderId, "Executing"))

      case cmd: RecordLotCompleted =>
        state match {
          case s: Executing =>
            // Idempotent: skip if this lot was already recorded
            if (s.completedLotIds.contains(cmd.lotId))
              Effect.none
            else {
              val newCompletedCount = s.completedLotCount + 1
              if (newCompletedCount >= s.totalLots) {
                // All lots done — finalize with accumulated counts
                val finalPass = s.accumPassCount + cmd.passCount
                val finalScrap = s.accumScrapCount + cmd.scrapCount
                val finalRework = s.accumReworkCount + cmd.reworkCount
                Effect.persist(Seq(
                  LotCompletionRecorded(cmd.workOrderId, cmd.lotId, cmd.passCount, cmd.scrapCount, cmd.reworkCount),
                  WorkOrderCompleted(finalPass, finalScrap, finalRework)
                ))
              } else {
                Effect.persist(LotCompletionRecorded(cmd.workOrderId, cmd.lotId, cmd.passCount, cmd.scrapCount, cmd.reworkCount))
              }
            }
          case _ =>
            // Not in Executing — ignore
            Effect.none
        }
    }
  }
}
