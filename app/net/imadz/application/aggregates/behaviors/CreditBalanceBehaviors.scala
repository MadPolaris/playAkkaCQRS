package net.imadz.application.aggregates.behaviors

import akka.persistence.typed.scaladsl.Effect
import net.imadz.application.aggregates.CreditBalanceAggregate._
import net.imadz.common.CommonTypes.iMadzError
import net.imadz.common.application.CommandHandlerReplyingBehavior.runReplyingPolicy
import net.imadz.domain.entities.CreditBalanceEntity._
import net.imadz.domain.policy.{AddInitialOnlyOncePolicy, DepositPolicy, WithdrawPolicy}
import net.imadz.domain.services.TransferDomainService
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
      val currentBalance = state.accountBalance.getOrElse(amount.currency.getCurrencyCode, Money(BigDecimal(0), amount.currency))
      TransferDomainService.validateTransfer(transferId, state.reservedAmount, currentBalance, amount) match {
        case Right(_) =>
          Effect.persist(FundsReserved(transferId, amount))
            .thenReply(replyTo)(_ => FundsReservationConfirmation(transferId, None))
        case Left(iMadzError("60008", _)) =>
          Effect.reply(replyTo)(FundsReservationConfirmation(transferId, None))
        case Left(error) =>
          Effect.reply(replyTo)(FundsReservationConfirmation(transferId, Some(error)))
      }
    case DeductFunds(transferId, replyTo) =>
      state.reservedAmount.get(transferId) match {
        case Some(reservedAmount) =>
          Effect.persist(FundsDeducted(transferId, reservedAmount))
            .thenReply(replyTo)(_ => FundsDeductionConfirmation(transferId, None))
        case None =>
          Effect.reply(replyTo)(FundsDeductionConfirmation(transferId, Some(iMadzError("60006", "No reserved funds found for this transfer"))))
      }
    case ReleaseReservedFunds(transferId, replyTo) =>
      state.reservedAmount.get(transferId) match {
        case Some(reservedAmount) =>
          Effect.persist(ReservationReleased(transferId, reservedAmount))
            .thenReply(replyTo)(_ => FundsReleaseConfirmation(transferId, None))
        case None =>
          Effect.reply(replyTo)(FundsReleaseConfirmation(transferId, Some(iMadzError("60006", "No reserved funds found for this transfer"))))
      }
  }

  private def incomingCreditBehaviors(state: CreditBalanceState): PartialFunction[CreditBalanceCommand, Effect[CreditBalanceEvent, CreditBalanceState]] = {

    case RecordIncomingCredits(transferId, amount, replyTo) =>
      state.incomingCredits.get(transferId) match {
        case Some(_) =>
          Effect
            .reply(replyTo)(RecordIncomingCreditsConfirmation(transferId = transferId, error = Some(iMadzError("60007", "incoming credits already registered"))))
        case None =>
          Effect
            .persist(IncomingCreditsRecorded(transferId, amount))
            .thenReply(replyTo)(_ => RecordIncomingCreditsConfirmation(transferId, None))
      }
    case CommitIncomingCredits(transferId, replyTo) =>
      state.incomingCredits.get(transferId) match {
        case Some(_) =>
          Effect
            .persist(IncomingCreditsCommited(transferId))
            .thenReply(replyTo)(_ => CommitIncomingCreditsConfirmation(transferId, None))
        case None =>
          Effect.reply(replyTo)(CommitIncomingCreditsConfirmation(transferId, Some(iMadzError("60008", "incoming credits cannot be found or already be committed before"))))
      }
    case CancelIncomingCredit(transferId, replyTo) =>
      state.incomingCredits.get(transferId) match {
        case Some(_) =>
          Effect.persist(IncomingCreditsCanceled(transferId))
            .thenReply(replyTo)(_ => CancelIncomingCreditConfirmation(transferId, None))
        case None =>
          Effect
            .reply(replyTo)(CancelIncomingCreditConfirmation(transferId, Some(iMadzError("60009", "incoming credits cannot be found or already be canceled before"))))

      }
  }

  private def mkLatestBalance[Param](param: Param)(updatedState: CreditBalanceState) =
    CreditBalanceConfirmation(None, updatedState.accountBalance.values.toList)

  private def mkError[Param](param: Param)(error: iMadzError) =
    CreditBalanceConfirmation(Some(error), Nil)

}
