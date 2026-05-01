package net.imadz.infra.saga

import net.imadz.common.CborSerializable

/**
 * Defines the phases of a Saga transaction.
 */
object SagaPhase {
  /**
   * Represents a single phase in the Saga lifecycle.
   */
  sealed trait TransactionPhase extends CborSerializable {
    val key: String = toString
  }

  /**
   * The initial phase where operations are executed.
   */
  case object PreparePhase extends TransactionPhase {
    override def toString: String = "prepare"
  }

  /**
   * The phase where operations are committed (if applicable).
   */
  case object CommitPhase extends TransactionPhase {
    override def toString: String = "commit"
  }

  /**
   * The phase where operations are undone due to a failure in Prepare or Commit phases.
   */
  case object CompensatePhase extends TransactionPhase {
    override def toString: String = "compensate"
  }
}
