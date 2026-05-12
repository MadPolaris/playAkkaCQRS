package net.imadz.application.aggregates.process

import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.eventstream.EventStream
import akka.persistence.typed.scaladsl.Effect
import net.imadz.domain.entities.FabProcessEntity._
import FabProcessProtocol._

object FabProcessBehaviors {

  def apply(context: ActorContext[FabProcessCommand]): FabProcessCommandHandler = (state, command) => command match {

    case cmd: StartProcess =>
      if (state.phase != ProcessCreated)
        Effect.reply(cmd.replyTo)(ProcessConfirmation(state.processId, state.phase.toString))
      else
        Effect.persist(ProcessStarted(cmd.lotId, cmd.waferIds, cmd.lotSize))
          .thenRun { (_: FabProcessState) => publishEvent(context, state.processId, ProcessStarted(cmd.lotId, cmd.waferIds, cmd.lotSize)) }
          .thenReply(cmd.replyTo)(s => ProcessConfirmation(s.processId, s.phase.toString))

    case cmd: RecordFoupLoaded =>
      Effect.persist(FoupLoaded(cmd.foupId, cmd.stockerId))
        .thenRun { (_: FabProcessState) => publishEvent(context, state.processId, FoupLoaded(cmd.foupId, cmd.stockerId)) }
        .thenReply(cmd.replyTo)(s => ProcessConfirmation(s.processId, s.phase.toString))

    case cmd: RecordTransportStarted =>
      Effect.persist(TransportStarted(cmd.foupId, cmd.fromArea, cmd.toArea, cmd.estimatedMs))
        .thenRun { (_: FabProcessState) => publishEvent(context, state.processId, TransportStarted(cmd.foupId, cmd.fromArea, cmd.toArea, cmd.estimatedMs)) }
        .thenReply(cmd.replyTo)(s => ProcessConfirmation(s.processId, s.phase.toString))

    case cmd: RecordTransportCompleted =>
      Effect.persist(TransportCompleted(cmd.foupId, cmd.equipmentId))
        .thenRun { (_: FabProcessState) => publishEvent(context, state.processId, TransportCompleted(cmd.foupId, cmd.equipmentId)) }
        .thenReply(cmd.replyTo)(s => ProcessConfirmation(s.processId, s.phase.toString))

    case cmd: RecordEquipmentJobStarted =>
      Effect.persist(EquipmentJobStarted(cmd.equipmentId, cmd.recipeId))
        .thenRun { (_: FabProcessState) => publishEvent(context, state.processId, EquipmentJobStarted(cmd.equipmentId, cmd.recipeId)) }
        .thenReply(cmd.replyTo)(s => ProcessConfirmation(s.processId, s.phase.toString))

    case cmd: RecordEquipmentJobCompleted =>
      Effect.persist(EquipmentJobCompleted(cmd.equipmentId, cmd.jobId, cmd.success))
        .thenRun { (_: FabProcessState) => publishEvent(context, state.processId, EquipmentJobCompleted(cmd.equipmentId, cmd.jobId, cmd.success)) }
        .thenReply(cmd.replyTo)(s => ProcessConfirmation(s.processId, s.phase.toString))

    case cmd: RecordWaferMeasured =>
      Effect.persist(WaferMeasured(cmd.waferId, cmd.cdNm))
        .thenRun { (_: FabProcessState) => publishEvent(context, state.processId, WaferMeasured(cmd.waferId, cmd.cdNm)) }
        .thenReply(cmd.replyTo)(s => ProcessConfirmation(s.processId, s.phase.toString))

    case cmd: RecordWaferClassified =>
      Effect.persist(WaferClassified(cmd.waferId, cmd.classification, cmd.reworkCount, cmd.cdValue))
        .thenRun { (_: FabProcessState) => publishEvent(context, state.processId, WaferClassified(cmd.waferId, cmd.classification, cmd.reworkCount, cmd.cdValue)) }
        .thenReply(cmd.replyTo)(s => ProcessConfirmation(s.processId, s.phase.toString))

    case cmd: RecordWafersSplitForRework =>
      Effect.persist(WafersSplitForRework(cmd.reworkWaferIds, cmd.scrapWaferIds, cmd.iteration))
        .thenRun { (_: FabProcessState) => publishEvent(context, state.processId, WafersSplitForRework(cmd.reworkWaferIds, cmd.scrapWaferIds, cmd.iteration)) }
        .thenReply(cmd.replyTo)(s => ProcessConfirmation(s.processId, s.phase.toString))

    case cmd: RecordWafersReworked =>
      Effect.persist(WafersReworked(cmd.waferIds))
        .thenRun { (_: FabProcessState) => publishEvent(context, state.processId, WafersReworked(cmd.waferIds)) }
        .thenReply(cmd.replyTo)(s => ProcessConfirmation(s.processId, s.phase.toString))

    case cmd: CompleteProcess =>
      Effect.persist(ProcessCompleted(cmd.lotId, cmd.passCount, cmd.scrapCount, cmd.reworkCount))
        .thenRun { (_: FabProcessState) => publishEvent(context, state.processId, ProcessCompleted(cmd.lotId, cmd.passCount, cmd.scrapCount, cmd.reworkCount)) }
        .thenReply(cmd.replyTo)(s => ProcessConfirmation(s.processId, s.phase.toString))
  }

  private def publishEvent(context: ActorContext[FabProcessCommand], processId: String, event: FabProcessEvent): Unit = {
    context.system.eventStream ! EventStream.Publish(ProcessEventEnvelope(processId, event))
  }
}
