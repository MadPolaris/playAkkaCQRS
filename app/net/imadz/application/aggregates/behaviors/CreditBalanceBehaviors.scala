package net.imadz.application.aggregates.behaviors

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import net.imadz.application.aggregates.CreditBalanceProtocol._
import net.imadz.common.application.CommandHandlerReplyingBehavior.runReplyingPolicy
import net.imadz.domain.entities.CreditBalanceEntity._
import net.imadz.domain.invariants._

object CreditBalanceBehaviors extends CreditBalanceCommandHelpers {

  // --- 主入口 ---
  def apply(context: ActorContext[CreditBalanceCommand]): CreditBalanceCommandHandler = (state, command) =>
    directBehaviors(context)(state)
      .orElse(reserveBehaviors(context)(state))
      .orElse(incomingCreditBehaviors(context)(state))
      .apply(command)

  // --- 行为分组 1: 直接操作 (Direct) ---
  private def directBehaviors(context: ActorContext[CreditBalanceCommand])(state: CreditBalanceState): PartialFunction[CreditBalanceCommand, Effect[CreditBalanceEvent, CreditBalanceState]] = {
    case cmd: AddInitial =>
      runReplyingPolicy(AddInitialOnlyOnceRule, AddInitialHelper)(state, cmd).replyWithAndPublish(cmd.replyTo)(context)

    case cmd: Withdraw =>
      runReplyingPolicy(WithdrawRule, WithdrawHelper)(state, cmd).replyWithAndPublish(cmd.replyTo)(context)

    case cmd: Deposit =>
      runReplyingPolicy(DepositRule, DepositHelper)(state, cmd).replyWithAndPublish(cmd.replyTo)(context)

    case GetBalance(replyTo) =>
      Effect.reply(replyTo)(CreditBalanceConfirmation(None, state.accountBalance.values.toList))
  }

  // --- 行为分组 2: 预留资金 (Reserve) ---
  private def reserveBehaviors(context: ActorContext[CreditBalanceCommand])(state: CreditBalanceState): PartialFunction[CreditBalanceCommand, Effect[CreditBalanceEvent, CreditBalanceState]] = {
    case cmd: ReserveFunds =>
      runReplyingPolicy(ReserveFundsRule, ReserveFundsHelper)(state, cmd).replyWithAndPublish(cmd.replyTo)(context)

    case cmd: DeductFunds =>
      runReplyingPolicy(DeductFundsRule, DeductFundsHelper)(state, cmd).replyWithAndPublish(cmd.replyTo)(context)

    case cmd: ReleaseReservedFunds =>
      runReplyingPolicy(ReleaseReservedFundsRule, ReleaseReservedFundsHelper)(state, cmd).replyWithAndPublish(cmd.replyTo)(context)
  }

  // --- 行为分组 3: 入账处理 (Incoming Credit) ---
  private def incomingCreditBehaviors(context: ActorContext[CreditBalanceCommand])(state: CreditBalanceState): PartialFunction[CreditBalanceCommand, Effect[CreditBalanceEvent, CreditBalanceState]] = {
    case cmd: RecordIncomingCredits =>
      runReplyingPolicy(RecordIncomingCreditsRule, RecordIncomingCreditsHelper)(state, cmd).replyWithAndPublish(cmd.replyTo)(context)

    case cmd: CommitIncomingCredits =>
      runReplyingPolicy(CommitIncomingCreditsRule, CommitIncomingCreditsHelper)(state, cmd).replyWithAndPublish(cmd.replyTo)(context)

    case cmd: CancelIncomingCredit =>
      runReplyingPolicy(CancelIncomingCreditRule, CancelIncomingCreditHelper)(state, cmd).replyWithAndPublish(cmd.replyTo)(context)
  }

}