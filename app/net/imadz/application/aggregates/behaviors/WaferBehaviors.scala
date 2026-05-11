package net.imadz.application.aggregates.behaviors

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import net.imadz.application.aggregates.WaferProtocol._
import net.imadz.common.application.CommandHandlerReplyingBehavior.runReplyingPolicy
import net.imadz.domain.entities.WaferEntity._
import net.imadz.domain.invariants.WaferInvariants._

object WaferBehaviors extends WaferCommandHelpers {

  def apply(context: ActorContext[WaferCommand]): WaferCommandHandler = (state, command) =>
    directBehaviors(context)(state)
      .orElse(transferBehaviors(context)(state))
      .apply(command)

  // Group 1: Lifecycle & Direct
  private def directBehaviors(context: ActorContext[WaferCommand])(state: WaferState): PartialFunction[WaferCommand, Effect[WaferEvent, WaferState]] = {
    case cmd: CreateWafer =>
      runReplyingPolicy(CreateWaferRule, CreateWaferHelper)(state, cmd).replyWithAndPublish(cmd.replyTo)(context)

    case cmd: ScrapWafer =>
      runReplyingPolicy(ScrapWaferRule, ScrapWaferHelper)(state, cmd).replyWithAndPublish(cmd.replyTo)(context)

    case cmd: ChangeStatus =>
      runReplyingPolicy(ChangeStatusRule, ChangeStatusHelper)(state, cmd).replyWithAndPublish(cmd.replyTo)(context)

    case GetWaferState(replyTo) =>
      Effect.reply(replyTo)(WaferConfirmation(None, Some(state.status), state.lotId))
  }

  // Group 2: Transfer — reserve/commit/release ownership transfer
  private def transferBehaviors(context: ActorContext[WaferCommand])(state: WaferState): PartialFunction[WaferCommand, Effect[WaferEvent, WaferState]] = {
    case cmd: ReserveTransfer =>
      runReplyingPolicy(ReserveTransferRule, ReserveTransferHelper)(state, cmd).replyWithAndPublish(cmd.replyTo)(context)

    case cmd: CommitTransfer =>
      runReplyingPolicy(CommitTransferRule, CommitTransferHelper)(state, cmd).replyWithAndPublish(cmd.replyTo)(context)

    case cmd: ReleaseTransfer =>
      runReplyingPolicy(ReleaseTransferRule, ReleaseTransferHelper)(state, cmd).replyWithAndPublish(cmd.replyTo)(context)
  }
}
