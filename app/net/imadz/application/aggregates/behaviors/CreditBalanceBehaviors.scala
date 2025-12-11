package net.imadz.application.aggregates.behaviors

import akka.persistence.typed.scaladsl.Effect
import net.imadz.application.aggregates.CreditBalanceAggregate._
import net.imadz.common.CommonTypes.{Id, iMadzError}
import net.imadz.common.application.CommandHandlerReplyingBehavior.runReplyingPolicy
import net.imadz.domain.entities.CreditBalanceEntity._
import net.imadz.domain.policy._
import net.imadz.domain.values.Money

object CreditBalanceBehaviors {

  def apply: CreditBalanceCommandHandler = (state, command) =>
    directBehaviors(state)
      .orElse(reserveBehaviors(state))
      .orElse(incomingCreditBehaviors(state))
      .apply(command)

  private def directBehaviors(state: CreditBalanceState): PartialFunction[CreditBalanceCommand, Effect[CreditBalanceEvent, CreditBalanceState]] = {
    case AddInitial(initial, replyTo) =>
      runReplyingPolicy(AddInitialOnlyOncePolicy)(state, initial)
        .replyWith(replyTo)(mkError, mkLatestBalance)
    case Withdraw(change, replyTo) =>
      runReplyingPolicy(WithdrawPolicy)(state, change)
        .replyWith(replyTo)(mkError, mkLatestBalance)
    case Deposit(change, replyTo) =>
      runReplyingPolicy(DepositPolicy)(state, change)
        .replyWith(replyTo)(mkError, mkLatestBalance)
    case GetBalance(replyTo) =>
      Effect.reply(replyTo)(mkLatestBalance(None)(state))
  }

  private def reserveBehaviors(state: CreditBalanceState): PartialFunction[CreditBalanceCommand, Effect[CreditBalanceEvent, CreditBalanceState]] = {
    case ReserveFunds(transferId, amount, replyTo) =>
      runReplyingPolicy(ReserveFundsPolicy)(state, (transferId, amount))
        .replyWith(replyTo)(mkReserveError, mkReserveSuccess)
    case DeductFunds(transferId, replyTo) =>
      runReplyingPolicy(DeductFundsPolicy)(state, transferId)
        .replyWith(replyTo)(mkDeductError, mkDeductSuccess)
    case ReleaseReservedFunds(transferId, replyTo) =>
      runReplyingPolicy(ReleaseReservedFundsPolicy)(state, transferId)
        .replyWith(replyTo)(mkReleaseError, mkReleaseSuccess)
  }

  private def incomingCreditBehaviors(state: CreditBalanceState): PartialFunction[CreditBalanceCommand, Effect[CreditBalanceEvent, CreditBalanceState]] = {
    case RecordIncomingCredits(transferId, amount, replyTo) =>
      runReplyingPolicy(RecordIncomingCreditsPolicy)(state, (transferId, amount))
        .replyWith(replyTo)(mkRecordError, mkRecordSuccess)
    case CommitIncomingCredits(transferId, replyTo) =>
      runReplyingPolicy(CommitIncomingCreditsPolicy)(state, transferId)
        .replyWith(replyTo)(mkCommitError, mkCommitSuccess)
    case CancelIncomingCredit(transferId, replyTo) =>
      runReplyingPolicy(CancelIncomingCreditPolicy)(state, transferId)
        .replyWith(replyTo)(mkCancelError, mkCancelSuccess)
  }

  private def mkLatestBalance[Param](param: Param)(updatedState: CreditBalanceState) =
    CreditBalanceConfirmation(None, updatedState.accountBalance.values.toList)

  private def mkError[Param](param: Param)(error: iMadzError) =
    CreditBalanceConfirmation(Some(error), Nil)

  private def mkRecordError(param: (Id, Money))(error: iMadzError) =
    RecordIncomingCreditsConfirmation(param._1, Some(error))

  private def mkRecordSuccess(param: (Id, Money))(state: CreditBalanceState) =
    RecordIncomingCreditsConfirmation(param._1, None)

  // 2. Commit (Param: Id)
  private def mkCommitError(id: Id)(error: iMadzError) =
    CommitIncomingCreditsConfirmation(id, Some(error))

  private def mkCommitSuccess(id: Id)(state: CreditBalanceState) =
    CommitIncomingCreditsConfirmation(id, None)

  // 3. Cancel (Param: Id)
  private def mkCancelError(id: Id)(error: iMadzError) =
    CancelIncomingCreditConfirmation(id, Some(error))

  private def mkCancelSuccess(id: Id)(state: CreditBalanceState) =
    CancelIncomingCreditConfirmation(id, None)

  private def mkReserveError(param: (Id, Money))(error: iMadzError) = FundsReservationConfirmation(param._1, Some(error))

  private def mkReserveSuccess(param: (Id, Money))(state: CreditBalanceState) = FundsReservationConfirmation(param._1, None)

  private def mkDeductError(id: Id)(error: iMadzError) = FundsDeductionConfirmation(id, Some(error))

  private def mkDeductSuccess(id: Id)(state: CreditBalanceState) = FundsDeductionConfirmation(id, None)

  private def mkReleaseError(id: Id)(error: iMadzError) = FundsReleaseConfirmation(id, Some(error))

  private def mkReleaseSuccess(id: Id)(state: CreditBalanceState) = FundsReleaseConfirmation(id, None)
}
