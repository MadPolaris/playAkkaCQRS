package net.imadz.application.aggregates.behaviors

import akka.persistence.typed.scaladsl.Effect
import net.imadz.application.aggregates.CreditBalanceAggregate._
import net.imadz.common.application.CommandHandlerReplyingBehavior.runReplyingPolicy
import net.imadz.domain.entities.CreditBalanceEntity._
import net.imadz.domain.invariants._

object CreditBalanceBehaviors extends CreditBalanceCommandHelpers {

  // --- 主入口 ---
  def apply: CreditBalanceCommandHandler = (state, command) =>
    directBehaviors(state)
      .orElse(reserveBehaviors(state))
      .orElse(incomingCreditBehaviors(state))
      .apply(command)

  // --- 行为分组 1: 直接操作 (Direct) ---
  private def directBehaviors(state: CreditBalanceState): PartialFunction[CreditBalanceCommand, Effect[CreditBalanceEvent, CreditBalanceState]] = {
    case cmd: AddInitial =>
      runReplyingPolicy(AddInitialOnlyOnceRule, AddInitialHelper)(state, cmd).replyWith(cmd.replyTo)

    case cmd: Withdraw =>
      runReplyingPolicy(WithdrawRule, WithdrawHelper)(state, cmd).replyWith(cmd.replyTo)

    case cmd: Deposit =>
      runReplyingPolicy(DepositRule, DepositHelper)(state, cmd).replyWith(cmd.replyTo)

    case GetBalance(replyTo) =>
      Effect.reply(replyTo)(CreditBalanceConfirmation(None, state.accountBalance.values.toList))
  }

  // --- 行为分组 2: 预留资金 (Reserve) ---
  private def reserveBehaviors(state: CreditBalanceState): PartialFunction[CreditBalanceCommand, Effect[CreditBalanceEvent, CreditBalanceState]] = {
    case cmd: ReserveFunds =>
      runReplyingPolicy(ReserveFundsRule, ReserveFundsHelper)(state, cmd).replyWith(cmd.replyTo)

    case cmd: DeductFunds =>
      runReplyingPolicy(DeductFundsRule, DeductFundsHelper)(state, cmd).replyWith(cmd.replyTo)

    case cmd: ReleaseReservedFunds =>
      runReplyingPolicy(ReleaseReservedFundsRule, ReleaseReservedFundsHelper)(state, cmd).replyWith(cmd.replyTo)
  }

  // --- 行为分组 3: 入账处理 (Incoming Credit) ---
  private def incomingCreditBehaviors(state: CreditBalanceState): PartialFunction[CreditBalanceCommand, Effect[CreditBalanceEvent, CreditBalanceState]] = {
    case cmd: RecordIncomingCredits =>
      runReplyingPolicy(RecordIncomingCreditsRule, RecordIncomingCreditsHelper)(state, cmd).replyWith(cmd.replyTo)

    case cmd: CommitIncomingCredits =>
      runReplyingPolicy(CommitIncomingCreditsRule, CommitIncomingCreditsHelper)(state, cmd).replyWith(cmd.replyTo)

    case cmd: CancelIncomingCredit =>
      runReplyingPolicy(CancelIncomingCreditRule, CancelIncomingCreditHelper)(state, cmd).replyWith(cmd.replyTo)
  }

}