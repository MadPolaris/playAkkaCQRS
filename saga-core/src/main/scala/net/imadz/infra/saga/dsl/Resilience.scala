package net.imadz.infra.saga.dsl

import net.imadz.infra.saga.SagaParticipant.{NonRetryableFailure, RetryableFailure, RetryableOrNotException}
import net.imadz.infra.saga.StepExecutor.CircuitBreakerSettings

import scala.concurrent.duration._

sealed trait ErrorAction
object ErrorAction {
  case object Retryable extends ErrorAction
  case object NonRetryable extends ErrorAction
}

/**
 * Dual-track error classification:
 *  - `business` classifies the Left(E) produced by a PhaseAsk mapReply at the ask boundary,
 *    while E is still typed. Unmatched business errors default to NonRetryable.
 *  - `thrown` classifies exceptions on top of the default matrix
 *    (Timeout/AskTimeout/Connect/SQLTransient -> Retryable, IllegalArgumentException and
 *    everything else -> NonRetryable). An explicit thrown rule overrides the default matrix.
 *
 * `describe` renders the business error into the failure message (the engine never sees E).
 */
final class ErrorRules[E] private (
    val business: PartialFunction[E, ErrorAction],
    val thrown: PartialFunction[Throwable, ErrorAction],
    describeFn: E => String
) {
  import ErrorAction._

  def describe(e: E): String = describeFn(e)

  def classifyBusiness(e: E): RetryableOrNotException = {
    val action = business.lift(e).getOrElse(NonRetryable)
    action match {
      case Retryable    => RetryableFailure(describe(e))
      case NonRetryable => NonRetryableFailure(describe(e))
    }
  }

  def classifyThrown(t: Throwable): RetryableOrNotException = {
    val action = thrown.lift(t).orElse(defaultMatrix.lift(t)).getOrElse(NonRetryable)
    action match {
      case Retryable    => RetryableFailure(if (t.getMessage != null) t.getMessage else t.getClass.getName)
      case NonRetryable => NonRetryableFailure(s"${t.getClass.getName}: ${t.getMessage}")
    }
  }

  private val defaultMatrix: PartialFunction[Throwable, ErrorAction] = {
    case _: java.util.concurrent.TimeoutException => Retryable // covers akka.pattern.AskTimeoutException
    case _: java.net.ConnectException             => Retryable
    case _: java.sql.SQLTransientException        => Retryable
    case _: IllegalArgumentException              => NonRetryable
  }
}

object ErrorRules {
  def apply[E](
      business: PartialFunction[E, ErrorAction] = PartialFunction.empty,
      thrown: PartialFunction[Throwable, ErrorAction] = PartialFunction.empty
  ): ErrorRules[E] = new ErrorRules[E](business, thrown, _.toString)

  def apply[E](business: PartialFunction[E, ErrorAction], describe: E => String): ErrorRules[E] =
    new ErrorRules[E](business, PartialFunction.empty, describe)

  def apply[E](business: PartialFunction[E, ErrorAction], thrown: PartialFunction[Throwable, ErrorAction], describe: E => String): ErrorRules[E] =
    new ErrorRules[E](business, thrown, describe)

  def none[E]: ErrorRules[E] = apply[E]()
}

sealed trait RecoveryBehavior
object RecoveryBehavior {
  /** Re-issue the operation after a crash while the step was Ongoing. */
  case object RetryIfOngoing extends RecoveryBehavior
  /** Fail the step after a crash while the step was Ongoing (compensation follows). */
  case object FailIfOngoing extends RecoveryBehavior
}

final case class ResiliencePolicy(
    maxRetries: Int = 3,
    timeoutPerAttempt: FiniteDuration = 5.seconds,
    recovery: RecoveryBehavior = RecoveryBehavior.FailIfOngoing,
    circuitBreaker: Option[CircuitBreakerSettings] = None
)

object ResiliencePolicy {
  /** Passing exactly this value on a SagaStep means "inherit the definition-level default". */
  val defaults: ResiliencePolicy = ResiliencePolicy()
}
