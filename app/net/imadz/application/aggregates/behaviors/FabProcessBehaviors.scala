package net.imadz.application.aggregates.behaviors

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import net.imadz.application.aggregates.FabProcessProtocol._
import net.imadz.domain.entities.FabProcessEntity._

object FabProcessBehaviors {

  def apply(context: ActorContext[FabProcessCommand]): FabProcessCommandHandler = (state, command) => command match {

    case cmd: StartProcess =>
      if (state.phase != ProcessCreated)
        Effect.reply(cmd.replyTo)(ProcessConfirmation(state.processId, state.phase.toString))
      else
        Effect.persist(ProcessStarted(cmd.lotId, cmd.waferIds, cmd.lotSize))
          .thenReply(cmd.replyTo)(s => ProcessConfirmation(s.processId, s.phase.toString))

    case cmd: RecordFoupLoaded =>
      Effect.persist(FoupLoaded(cmd.foupId, cmd.stockerId))
        .thenReply(cmd.replyTo)(s => ProcessConfirmation(s.processId, s.phase.toString))

    case cmd: RecordTransportStarted =>
      Effect.persist(TransportStarted(cmd.foupId, cmd.fromArea, cmd.toArea, cmd.estimatedMs))
        .thenReply(cmd.replyTo)(s => ProcessConfirmation(s.processId, s.phase.toString))

    case cmd: RecordTransportCompleted =>
      Effect.persist(TransportCompleted(cmd.foupId, cmd.equipmentId))
        .thenReply(cmd.replyTo)(s => ProcessConfirmation(s.processId, s.phase.toString))

    case cmd: RecordEquipmentJobStarted =>
      Effect.persist(EquipmentJobStarted(cmd.equipmentId, cmd.recipeId))
        .thenReply(cmd.replyTo)(s => ProcessConfirmation(s.processId, s.phase.toString))

    case cmd: RecordEquipmentJobCompleted =>
      Effect.persist(EquipmentJobCompleted(cmd.equipmentId, cmd.jobId, cmd.success))
        .thenReply(cmd.replyTo)(s => ProcessConfirmation(s.processId, s.phase.toString))

    case cmd: RecordWaferMeasured =>
      Effect.persist(WaferMeasured(cmd.waferId, cmd.cdNm))
        .thenReply(cmd.replyTo)(s => ProcessConfirmation(s.processId, s.phase.toString))

    case cmd: RecordWaferClassified =>
      if (state.waferClassifications.contains(cmd.waferId))
        Effect.reply(cmd.replyTo)(ProcessConfirmation(state.processId, state.phase.toString))
      else
        Effect.persist(WaferClassified(cmd.waferId, cmd.classification, cmd.reworkCount, cmd.cdValue))
          .thenReply(cmd.replyTo)(s => ProcessConfirmation(s.processId, s.phase.toString))

    case cmd: RecordWafersSplitForRework =>
      Effect.persist(WafersSplitForRework(cmd.reworkWaferIds, cmd.scrapWaferIds, cmd.iteration))
        .thenReply(cmd.replyTo)(s => ProcessConfirmation(s.processId, s.phase.toString))

    case cmd: RecordWafersReworked =>
      Effect.persist(WafersReworked(cmd.waferIds))
        .thenReply(cmd.replyTo)(s => ProcessConfirmation(s.processId, s.phase.toString))

    case cmd: RecordWafersSentAsPilot =>
      Effect.persist(WafersSentAsPilot(cmd.waferIds))
        .thenReply(cmd.replyTo)(s => ProcessConfirmation(s.processId, s.phase.toString))

    case cmd: RecordWafersSampled =>
      Effect.persist(WafersSampled(cmd.sampleIds, cmd.skipIds))
        .thenReply(cmd.replyTo)(s => ProcessConfirmation(s.processId, s.phase.toString))

    case cmd: RecordWafersHeld =>
      Effect.persist(WafersHeld(cmd.waferIds, cmd.reason))
        .thenReply(cmd.replyTo)(s => ProcessConfirmation(s.processId, s.phase.toString))

    case cmd: RecordWafersReleased =>
      Effect.persist(WafersReleased(cmd.waferIds))
        .thenReply(cmd.replyTo)(s => ProcessConfirmation(s.processId, s.phase.toString))

    case cmd: CompleteProcess =>
      if (state.phase == ProcessCompleted)
        Effect.reply(cmd.replyTo)(ProcessConfirmation(state.processId, state.phase.toString))
      else
        Effect.persist(ProcessCompleted(cmd.lotId, cmd.passCount, cmd.scrapCount, cmd.reworkCount))
          .thenReply(cmd.replyTo)(s => ProcessConfirmation(s.processId, s.phase.toString))
  }
}
