package net.imadz.application.aggregates.behaviors


import akka.persistence.typed.scaladsl.Effect
import net.imadz.application.aggregates.TransactionAggregate._
import net.imadz.common.CommonTypes.iMadzError
import net.imadz.domain.entities.TransactionEntity._

object TransactionBehaviors {
  def apply: TransactionCommandHandler = (state, command) => command match {
    case InitiateTransaction(fromUserId, toUserId, amount, replyTo) =>
      val transactionId = java.util.UUID.randomUUID()
      Effect.persist(TransactionInitiated(fromUserId, toUserId, amount))
        .thenReply(replyTo)(_ => TransactionInitiationConfirmation(transactionId, None))

    case PrepareTransaction(id, replyTo) =>
      if (state.status == Initiated) {
        Effect.persist(TransactionPrepared(id))
          .thenReply(replyTo)(_ => TransactionPreparationConfirmation(id, None))
      } else {
        Effect.reply(replyTo)(TransactionPreparationConfirmation(id, Some(iMadzError("70001", "Invalid transaction state for preparation"))))
      }

    case CompleteTransaction(id, replyTo) =>
      if (state.status == Prepared) {
        Effect.persist(TransactionCompleted(id))
          .thenReply(replyTo)(_ => TransactionCompletionConfirmation(id, None))
      } else {
        Effect.reply(replyTo)(TransactionCompletionConfirmation(id, Some(iMadzError("70002", "Invalid transaction state for completion"))))
      }

    case FailTransaction(id, reason, replyTo) =>
      Effect.persist(TransactionFailed(id, reason))
        .thenReply(replyTo)(_ => TransactionFailureConfirmation(id, None))
  }
}