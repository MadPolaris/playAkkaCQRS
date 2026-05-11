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
      .apply(command)

  // Group 1: Lifecycle & Direct
  private def directBehaviors(context: ActorContext[LotCommand])(state: LotState): PartialFunction[LotCommand, Effect[LotEvent, LotState]] = {
    case cmd: CreateLot =>
      runReplyingPolicy(CreateLotRule, CreateLotHelper)(state, cmd).replyWithAndPublish(cmd.replyTo)(context)

    case cmd: SealLot =>
      runReplyingPolicy(SealLotRule, SealLotHelper)(state, cmd).replyWithAndPublish(cmd.replyTo)(context)

    case GetLotState(replyTo) =>
      Effect.reply(replyTo)(LotConfirmation(None, state.waferIds, Some(state.phase)))
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
}
