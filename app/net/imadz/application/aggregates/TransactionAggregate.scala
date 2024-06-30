package net.imadz.application.aggregates

import akka.actor.typed.ActorRef
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.typed.scaladsl.Effect
import net.imadz.common.CborSerializable
import net.imadz.common.CommonTypes.{Id, iMadzError}
import net.imadz.domain.entities.TransactionEntity._
import net.imadz.domain.values.Money

object TransactionAggregate {

  // Commands
  sealed trait TransactionCommand extends CborSerializable

  case class InitiateTransaction(fromUserId: Id, toUserId: Id, amount: Money, replyTo: ActorRef[TransactionInitiationConfirmation]) extends TransactionCommand

  case class PrepareTransaction(id: Id, replyTo: ActorRef[TransactionPreparationConfirmation]) extends TransactionCommand

  case class CompleteTransaction(id: Id, replyTo: ActorRef[TransactionCompletionConfirmation]) extends TransactionCommand

  case class FailTransaction(id: Id, reason: String, replyTo: ActorRef[TransactionFailureConfirmation]) extends TransactionCommand

  // Command Replies
  case class TransactionInitiationConfirmation(transactionId: Id, error: Option[iMadzError]) extends CborSerializable

  case class TransactionPreparationConfirmation(transactionId: Id, error: Option[iMadzError]) extends CborSerializable

  case class TransactionCompletionConfirmation(transactionId: Id, error: Option[iMadzError]) extends CborSerializable

  case class TransactionFailureConfirmation(transactionId: Id, error: Option[iMadzError]) extends CborSerializable

  // Command Handler
  type TransactionCommandHandler = (TransactionState, TransactionCommand) => Effect[TransactionEvent, TransactionState]

  // Akka
  val tags: Vector[String] = Vector.tabulate(5)(i => s"money-transfer-$i")
  val TransactionEntityTypeKey: EntityTypeKey[TransactionCommand] = EntityTypeKey("MoneyTransfer")
}
