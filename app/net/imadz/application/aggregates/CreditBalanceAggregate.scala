package net.imadz.application.aggregates

import akka.actor.typed.ActorRef
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.typed.scaladsl.Effect
import net.imadz.application.aggregates.behaviors.CreditBalanceBehaviors
import net.imadz.common.CommonTypes.{CborSerializable, Id, iMadzError}
import net.imadz.domain.entities.CreditBalanceEntity.{CreditBalanceEvent, CreditBalanceState}
import net.imadz.domain.values.Money

object CreditBalanceAggregate {

  // Commands Section
  sealed trait CreditBalanceCommand
  case class AddInitial(initial: Money, replyTo: ActorRef[CreditBalanceConfirmation]) extends CreditBalanceCommand
  case class Deposit(change: Money, replyTo: ActorRef[CreditBalanceConfirmation]) extends CreditBalanceCommand
  case class Withdraw(change: Money, replyTo: ActorRef[CreditBalanceConfirmation]) extends CreditBalanceCommand
  case class GetBalance(replyTo: ActorRef[CreditBalanceConfirmation]) extends CreditBalanceCommand

  case class ReserveFunds(transferId: Id, amount: Money, replyTo: ActorRef[FundsReservationConfirmation]) extends CreditBalanceCommand
  case class DeductFunds(transferId: Id, replyTo: ActorRef[FundsDeductionConfirmation]) extends CreditBalanceCommand
  case class ReleaseReservedFunds(transferId: Id, replyTo: ActorRef[FundsReleaseConfirmation]) extends CreditBalanceCommand

  case class RecordIncomingCredits(transferId: Id, amount: Money, replyTo: ActorRef[RecordIncomingCreditsConfirmation]) extends CreditBalanceCommand
  case class CommitIncomingCredits(transferId: Id, replyTo: ActorRef[CommitIncomingCreditsConfirmation]) extends CreditBalanceCommand
  case class CancelIncomingCredit(transferId: Id, replyTo: ActorRef[CancelIncomingCreditConfirmation]) extends CreditBalanceCommand



  // Command Replies
  case class CreditBalanceConfirmation(error: Option[iMadzError], balances: List[Money]) extends CborSerializable
  case class FundsReservationConfirmation(transferId: Id, error: Option[iMadzError]) extends CborSerializable
  case class FundsDeductionConfirmation(transferId: Id, error: Option[iMadzError]) extends CborSerializable
  case class FundsReleaseConfirmation(transferId: Id, error: Option[iMadzError]) extends CborSerializable
  case class RecordIncomingCreditsConfirmation(transferId: Id, error: Option[iMadzError]) extends CborSerializable
  case class CommitIncomingCreditsConfirmation(transferId: Id, error: Option[iMadzError]) extends CborSerializable
  case class CancelIncomingCreditConfirmation(transferId: Id, error: Option[iMadzError]) extends CborSerializable

  case class TransferConfirmation(transferId: Id, error: Option[iMadzError]) extends CborSerializable

  // Command Handler
  type CreditBalanceCommandHandler = (CreditBalanceState, CreditBalanceCommand) => Effect[CreditBalanceEvent, CreditBalanceState]

  def commandHandler: CreditBalanceCommandHandler = CreditBalanceBehaviors.apply

  // Akka
  val CreditBalanceEntityTypeKey: EntityTypeKey[CreditBalanceCommand] = EntityTypeKey("CreditBalance")
  val tags: Vector[String] = Vector.tabulate(5)(i => s"credit-balance-$i")
}
