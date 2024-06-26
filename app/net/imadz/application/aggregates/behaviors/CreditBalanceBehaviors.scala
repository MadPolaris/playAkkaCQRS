package net.imadz.application.aggregates.behaviors

import akka.persistence.typed.scaladsl.Effect
import net.imadz.application.aggregates.CreditBalanceAggregate._
import net.imadz.common.CommonTypes.iMadzError
import net.imadz.common.application.CommandHandlerReplyingBehavior.runReplyingPolicy
import net.imadz.domain.entities.CreditBalanceEntity.{CreditBalanceState, FundsReserved, ReservationReleased, TransferCompleted}
import net.imadz.domain.policy.{AddInitialOnlyOncePolicy, DepositPolicy, WithdrawPolicy}
import net.imadz.domain.services.TransferDomainService
import net.imadz.domain.values.Money

object CreditBalanceBehaviors {

  def apply: CreditBalanceCommandHandler = (state, command) => command match {
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
    case ReserveFunds(transferId, amount, replyTo) =>
      val currentBalance = state.accountBalance.getOrElse(amount.currency.getCurrencyCode, Money(BigDecimal(0), amount.currency))
      TransferDomainService.validateTransfer(currentBalance, amount) match {
        case Right(_) =>
          Effect.persist(FundsReserved(transferId, amount))
            .thenReply(replyTo)(_ => FundsReservationConfirmation(transferId, None))
        case Left(error) =>
          Effect.reply(replyTo)(FundsReservationConfirmation(transferId, Some(error)))
      }

    case CommitTransfer(transferId, replyTo) =>
      state.reservedAmount.get(transferId) match {
        case Some(_) =>
          Effect.persist(TransferCompleted(transferId))
            .thenReply(replyTo)(_ => TransferConfirmation(transferId, None))
        case None =>
          Effect.reply(replyTo)(TransferConfirmation(transferId, Some(iMadzError("60005", "No reserved funds found for this transfer"))))
      }

    case ReleaseFundsReservation(transferId, replyTo) =>
      state.reservedAmount.get(transferId) match {
        case Some(reservedAmount) =>
          Effect.persist(ReservationReleased(transferId, reservedAmount))
            .thenReply(replyTo)(_ => FundsReservationConfirmation(transferId, None))
        case None =>
          Effect.reply(replyTo)(FundsReservationConfirmation(transferId, Some(iMadzError("60006", "No reserved funds found for this transfer"))))
      }
  }

  private def mkLatestBalance[Param](param: Param)(updatedState: CreditBalanceState) =
    CreditBalanceConfirmation(None, updatedState.accountBalance.values.toList)

  private def mkError[Param](param: Param)(error: iMadzError) =
    CreditBalanceConfirmation(Some(error), Nil)

}
