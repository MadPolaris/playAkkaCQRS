package net.imadz.application.aggregates.behaviors

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import net.imadz.application.aggregates.LotProtocol._
import net.imadz.common.application.CommandHandlerReplyingBehavior.runReplyingPolicy
import net.imadz.domain.entities.LotEntity._
import net.imadz.domain.invariants.LotInvariants._

object LotBehaviors extends LotCommandHelpers {

  def apply(context: ActorContext[LotCommand]): LotCommandHandler = (state, command) =>
    directBehaviors(context)(state)
      .orElse(sourceTransferBehaviors(context)(state))
      .orElse(targetTransferBehaviors(context)(state))
      .orElse(equipmentReportBehaviors(context)(state))
      .apply(command)

  // Group 1: Lifecycle & Direct
  private def directBehaviors(context: ActorContext[LotCommand])(state: LotState): PartialFunction[LotCommand, Effect[LotEvent, LotState]] = {
    case cmd: CreateLot =>
      runReplyingPolicy(CreateLotRule, CreateLotHelper)(state, cmd).replyWithAndPublish(cmd.replyTo)(context)

    case cmd: SealLot =>
      runReplyingPolicy(SealLotRule, SealLotHelper)(state, cmd).replyWithAndPublish(cmd.replyTo)(context)

    case GetLotState(replyTo) =>
      Effect.reply(replyTo)(LotConfirmation(
        error = None, waferIds = state.waferIds, phase = Some(state.phase),
        productId = Some(state.productId), lotId = Some(state.lotId),
        reservedWafers = state.reservedWafers, incomingWafers = state.incomingWafers,
        completedTransferIds = state.completedTransferIds,
        loadedFoupId = state.loadedFoupId,
        waferClassifications = state.waferClassifications.map { case (id, r) => id -> r.classification },
        waferStates = state.wafers,
        measuredWafers = state.measuredWafers
      ))
  }

  // Group 2: Source lot — reserve/commit/release outgoing wafers
  private def sourceTransferBehaviors(context: ActorContext[LotCommand])(state: LotState): PartialFunction[LotCommand, Effect[LotEvent, LotState]] = {
    case cmd: ReserveWaferRemoval =>
      runReplyingPolicy(ReserveWaferRemovalRule, ReserveWaferRemovalHelper)(state, cmd).replyWithAndPublish(cmd.replyTo)(context)

    case cmd: CommitWaferRemoval =>
      runReplyingPolicy(CommitWaferRemovalRule, CommitWaferRemovalHelper)(state, cmd).replyWithAndPublish(cmd.replyTo)(context)

    case cmd: ReleaseReservedWafer =>
      runReplyingPolicy(ReleaseReservedWaferRule, ReleaseReservedWaferHelper)(state, cmd).replyWithAndPublish(cmd.replyTo)(context)
  }

  // Group 3: Target lot — reserve/commit/cancel incoming wafers
  private def targetTransferBehaviors(context: ActorContext[LotCommand])(state: LotState): PartialFunction[LotCommand, Effect[LotEvent, LotState]] = {
    case cmd: ReserveAddWafer =>
      runReplyingPolicy(ReserveAddWaferRule, ReserveAddWaferHelper)(state, cmd).replyWithAndPublish(cmd.replyTo)(context)

    case cmd: CommitAddWafer =>
      runReplyingPolicy(CommitAddWaferRule, CommitAddWaferHelper)(state, cmd).replyWithAndPublish(cmd.replyTo)(context)

    case cmd: CancelAddWafer =>
      runReplyingPolicy(CancelAddWaferRule, CancelAddWaferHelper)(state, cmd).replyWithAndPublish(cmd.replyTo)(context)
  }

  // Group 4: Equipment reports — idempotent recording
  private def equipmentReportBehaviors(context: ActorContext[LotCommand])(state: LotState): PartialFunction[LotCommand, Effect[LotEvent, LotState]] = {
    case cmd: RecordFoupLoaded =>
      if (state.loadedFoupId.contains(cmd.foupId))
        Effect.reply(cmd.replyTo)(LotConfirmation(None, phase = Some(state.phase)))
      else
        Effect.persist(FoupLoaded(cmd.foupId, cmd.stockerId))
          .thenReply(cmd.replyTo)(s => LotConfirmation(None, phase = Some(s.phase)))

    case cmd: RecordTransportStarted =>
      Effect.persist(TransportStarted(cmd.foupId, cmd.fromArea, cmd.toArea, cmd.estimatedMs))
        .thenReply(cmd.replyTo)(s => LotConfirmation(None, phase = Some(s.phase)))

    case cmd: RecordTransportCompleted =>
      Effect.persist(TransportCompleted(cmd.foupId, cmd.equipmentId))
        .thenReply(cmd.replyTo)(s => LotConfirmation(None, phase = Some(s.phase)))

    case cmd: RecordEquipmentJobStarted =>
      Effect.persist(EquipmentJobStarted(cmd.equipmentId, cmd.recipeId))
        .thenReply(cmd.replyTo)(s => LotConfirmation(None, phase = Some(s.phase)))

    case cmd: RecordEquipmentJobCompleted =>
      Effect.persist(EquipmentJobCompleted(cmd.equipmentId, cmd.jobId, cmd.success))
        .thenReply(cmd.replyTo)(s => LotConfirmation(None, phase = Some(s.phase)))

    case cmd: RecordWaferMeasured =>
      if (state.wafers.get(cmd.waferId).exists(_.measured))
        Effect.reply(cmd.replyTo)(LotConfirmation(None, phase = Some(state.phase)))
      else
        Effect.persist(WaferMeasured(cmd.waferId, cmd.cdNm))
          .thenReply(cmd.replyTo)(s => LotConfirmation(None, phase = Some(s.phase)))

    case cmd: RecordWaferClassified =>
      val existing = state.wafers.get(cmd.waferId).flatMap(_.classification)
      if (existing.contains(cmd.classification) && state.wafers.get(cmd.waferId).exists(_.reworkCount == cmd.reworkCount))
        Effect.reply(cmd.replyTo)(LotConfirmation(None, phase = Some(state.phase)))
      else
        Effect.persist(WaferClassified(cmd.waferId, cmd.classification, cmd.reworkCount, cmd.cdValue))
          .thenReply(cmd.replyTo)(s => LotConfirmation(None, phase = Some(s.phase)))

    case cmd: RecordSubLotCreated =>
      Effect.persist(SubLotCreated(cmd.childLotId, cmd.splitReason, cmd.waferIds))
        .thenReply(cmd.replyTo)(s => LotConfirmation(None, phase = Some(s.phase)))

    case cmd: RecordSubLotMerged =>
      Effect.persist(SubLotMerged(cmd.childLotId, cmd.waferIds))
        .thenReply(cmd.replyTo)(s => LotConfirmation(None, phase = Some(s.phase)))

    case cmd: RecordSubLotScrapped =>
      Effect.persist(SubLotScrapped(cmd.childLotId, cmd.reason, cmd.waferIds))
        .thenReply(cmd.replyTo)(s => LotConfirmation(None, phase = Some(s.phase)))

    case cmd: RecordWafersSplitForRework =>
      Effect.persist(WafersSplitForRework(cmd.reworkWaferIds, cmd.scrapWaferIds, cmd.iteration))
        .thenReply(cmd.replyTo)(s => LotConfirmation(None, phase = Some(s.phase)))

    case cmd: RecordWafersReworked =>
      Effect.persist(WafersReworked(cmd.waferIds))
        .thenReply(cmd.replyTo)(s => LotConfirmation(None, phase = Some(s.phase)))

    case cmd: RecordWafersSentAsPilot =>
      Effect.persist(WafersSentAsPilot(cmd.waferIds))
        .thenReply(cmd.replyTo)(s => LotConfirmation(None, phase = Some(s.phase)))

    case cmd: RecordWafersSampled =>
      Effect.persist(WafersSampled(cmd.sampleIds, cmd.skipIds))
        .thenReply(cmd.replyTo)(s => LotConfirmation(None, phase = Some(s.phase)))

    case cmd: RecordWafersHeld =>
      Effect.persist(WafersHeld(cmd.waferIds, cmd.reason))
        .thenReply(cmd.replyTo)(s => LotConfirmation(None, phase = Some(s.phase)))

    case cmd: RecordWafersReleased =>
      Effect.persist(WafersReleased(cmd.waferIds))
        .thenReply(cmd.replyTo)(s => LotConfirmation(None, phase = Some(s.phase)))

    case cmd: CompleteProcess =>
      if (state.phase == AwaitingSubLot)
        Effect.reply(cmd.replyTo)(LotConfirmation(
          Some(new net.imadz.common.CommonTypes.iMadzError("LOT_041", s"Cannot complete process: lot ${state.lotId} is awaiting sub-lot result")),
          phase = Some(state.phase)))
      else
        Effect.persist(ProcessCompleted(cmd.lotId, cmd.passCount, cmd.scrapCount, cmd.reworkCount))
          .thenReply(cmd.replyTo)(s => LotConfirmation(None, phase = Some(s.phase)))

    case cmd: FailLot =>
      Effect.persist(LotFailed(cmd.reason, cmd.failedAt))
        .thenReply(cmd.replyTo)(s => LotConfirmation(None, phase = Some(s.phase)))

    // RouteCard commands (M3.5+)
    case cmd: AssignRouteCard =>
      runReplyingPolicy(AssignRouteCardRule, AssignRouteCardHelper)(state, cmd).replyWithAndPublish(cmd.replyTo)(context)

    case cmd: AdvanceRouteCardStep =>
      runReplyingPolicy(AdvanceRouteCardStepRule, AdvanceRouteCardStepHelper)(state, cmd).replyWithAndPublish(cmd.replyTo)(context)
  }
}
