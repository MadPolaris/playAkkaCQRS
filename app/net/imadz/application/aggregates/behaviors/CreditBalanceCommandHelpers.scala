package net.imadz.application.aggregates.behaviors

import net.imadz.application.aggregates.CreditBalanceAggregate._
import net.imadz.common.CommonTypes.{Id, iMadzError}
import net.imadz.common.application.CommandHandlerReplyingBehavior.CommandHelper
import net.imadz.domain.entities.CreditBalanceEntity._
import net.imadz.domain.values.Money

/**
 * 胶水代码库：负责将 Command 转换为 Policy 参数，并将结果包装为 Reply。
 * 这里是 AI 填空的主要战场。
 */
trait CreditBalanceCommandHelpers {

  // --- Group 1: Direct Operations ---

  implicit object AddInitialHelper extends CommandHelper[AddInitial, CreditBalanceState, Money, CreditBalanceConfirmation] {
    override def toParam(state: CreditBalanceState, command: AddInitial): Money = command.initial

    override def createFailureReply(param: Money)(error: iMadzError): CreditBalanceConfirmation = CreditBalanceConfirmation(Some(error), Nil)

    override def createSuccessReply(param: Money)(state: CreditBalanceState): CreditBalanceConfirmation =
      CreditBalanceConfirmation(None, state.accountBalance.values.toList)
  }

  implicit object WithdrawHelper extends CommandHelper[Withdraw, CreditBalanceState, Money, CreditBalanceConfirmation] {
    override def toParam(state: CreditBalanceState, command: Withdraw): Money = command.change

    override def createFailureReply(param: Money)(error: iMadzError): CreditBalanceConfirmation = CreditBalanceConfirmation(Some(error), Nil)

    override def createSuccessReply(param: Money)(state: CreditBalanceState): CreditBalanceConfirmation =
      CreditBalanceConfirmation(None, state.accountBalance.values.toList)
  }

  implicit object DepositHelper extends CommandHelper[Deposit, CreditBalanceState, Money, CreditBalanceConfirmation] {
    override def toParam(state: CreditBalanceState, command: Deposit): Money = command.change

    override def createFailureReply(param: Money)(error: iMadzError): CreditBalanceConfirmation = CreditBalanceConfirmation(Some(error), Nil)

    override def createSuccessReply(param: Money)(state: CreditBalanceState): CreditBalanceConfirmation =
      CreditBalanceConfirmation(None, state.accountBalance.values.toList)
  }

  // --- Group 2: Reservation Operations ---

  implicit object ReserveFundsHelper extends CommandHelper[ReserveFunds, CreditBalanceState, (Id, Money), FundsReservationConfirmation] {
    override def toParam(state: CreditBalanceState, command: ReserveFunds): (Id, Money) = (command.transferId, command.amount)

    override def createFailureReply(param: (Id, Money))(error: iMadzError): FundsReservationConfirmation = FundsReservationConfirmation(param._1, Some(error))

    override def createSuccessReply(param: (Id, Money))(state: CreditBalanceState): FundsReservationConfirmation = FundsReservationConfirmation(param._1, None)
  }

  implicit object DeductFundsHelper extends CommandHelper[DeductFunds, CreditBalanceState, Id, FundsDeductionConfirmation] {
    override def toParam(state: CreditBalanceState, command: DeductFunds): Id = command.transferId

    override def createFailureReply(param: Id)(error: iMadzError): FundsDeductionConfirmation = FundsDeductionConfirmation(param, Some(error))

    override def createSuccessReply(param: Id)(state: CreditBalanceState): FundsDeductionConfirmation = FundsDeductionConfirmation(param, None)
  }

  implicit object ReleaseReservedFundsHelper extends CommandHelper[ReleaseReservedFunds, CreditBalanceState, Id, FundsReleaseConfirmation] {
    override def toParam(state: CreditBalanceState, command: ReleaseReservedFunds): Id = command.transferId

    override def createFailureReply(param: Id)(error: iMadzError): FundsReleaseConfirmation = FundsReleaseConfirmation(param, Some(error))

    override def createSuccessReply(param: Id)(state: CreditBalanceState): FundsReleaseConfirmation = FundsReleaseConfirmation(param, None)
  }

  // --- Group 3: Incoming Credit Operations ---

  implicit object RecordIncomingCreditsHelper extends CommandHelper[RecordIncomingCredits, CreditBalanceState, (Id, Money), RecordIncomingCreditsConfirmation] {
    override def toParam(state: CreditBalanceState, command: RecordIncomingCredits): (Id, Money) = (command.transferId, command.amount)

    override def createFailureReply(param: (Id, Money))(error: iMadzError): RecordIncomingCreditsConfirmation = RecordIncomingCreditsConfirmation(param._1, Some(error))

    override def createSuccessReply(param: (Id, Money))(state: CreditBalanceState): RecordIncomingCreditsConfirmation = RecordIncomingCreditsConfirmation(param._1, None)
  }

  implicit object CommitIncomingCreditsHelper extends CommandHelper[CommitIncomingCredits, CreditBalanceState, Id, CommitIncomingCreditsConfirmation] {
    override def toParam(state: CreditBalanceState, command: CommitIncomingCredits): Id = command.transferId

    override def createFailureReply(param: Id)(error: iMadzError): CommitIncomingCreditsConfirmation = CommitIncomingCreditsConfirmation(param, Some(error))

    override def createSuccessReply(param: Id)(state: CreditBalanceState): CommitIncomingCreditsConfirmation = CommitIncomingCreditsConfirmation(param, None)
  }

  implicit object CancelIncomingCreditHelper extends CommandHelper[CancelIncomingCredit, CreditBalanceState, Id, CancelIncomingCreditConfirmation] {
    override def toParam(state: CreditBalanceState, command: CancelIncomingCredit): Id = command.transferId

    override def createFailureReply(param: Id)(error: iMadzError): CancelIncomingCreditConfirmation = CancelIncomingCreditConfirmation(param, Some(error))

    override def createSuccessReply(param: Id)(state: CreditBalanceState): CancelIncomingCreditConfirmation = CancelIncomingCreditConfirmation(param, None)
  }
}