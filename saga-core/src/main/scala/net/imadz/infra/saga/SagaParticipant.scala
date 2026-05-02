package net.imadz.infra.saga

import net.imadz.infra.saga.SagaParticipant._
import org.slf4j.{Logger, LoggerFactory}

import java.net.ConnectException
import java.sql.SQLTransientException
import scala.concurrent.{ExecutionContext, Future, TimeoutException}
import scala.util.{Failure, Success}

object SagaParticipant {
  case class SagaResult[T](
                            message: Option[String],
                            metadata: Map[String, String],
                            data: Option[T]
                          )

  object SagaResult {
    def empty[T](): SagaResult[T] = SagaResult[T](None, Map.empty[String, String], None)

    def apply[T](data: T): SagaResult[T] = SagaResult(None, Map.empty[String, String], Some(data))

    def apply[T](data: T, message: String): SagaResult[T] = SagaResult(Some(message), Map.empty[String, String], Some(data))
  }

  type ParticipantEffect[E, R] = Future[Either[E, SagaResult[R]]]

  sealed trait RetryableOrNotException {
    def message: String
  }

  case class RetryableFailure(message: String) extends RuntimeException(message) with RetryableOrNotException

  case class NonRetryableFailure(message: String) extends RuntimeException(message) with RetryableOrNotException

  /**
   * Throw or return this exception in doCompensate if the transaction is not found
   * or already rolled back. The Saga engine will treat it as a successful compensation.
   */
  case class CompensationIgnoredException(message: String = "Transaction not found or already rolled back") extends RuntimeException(message)

}

trait SagaParticipant[E, R, C] {

  protected def logger: Logger = LoggerFactory.getLogger(getClass)

  protected def doPrepare(transactionId: String, context: C, traceId: String): ParticipantEffect[E, R]

  protected def doCommit(transactionId: String, context: C, traceId: String): ParticipantEffect[E, R]

  protected def doCompensate(transactionId: String, context: C, traceId: String): ParticipantEffect[E, R]

  def prepare(transactionId: String, context: C, traceId: String)(implicit ec: ExecutionContext): ParticipantEffect[RetryableOrNotException, R] =
    executeWithRetryClassification(doPrepare(transactionId, context, traceId), traceId)

  def commit(transactionId: String, context: C, traceId: String)(implicit ec: ExecutionContext): ParticipantEffect[RetryableOrNotException, R] =
    executeWithRetryClassification(doCommit(transactionId, context, traceId), traceId)

  def compensate(transactionId: String, context: C, traceId: String)(implicit ec: ExecutionContext): ParticipantEffect[RetryableOrNotException, R] =
    executeWithRetryClassification(doCompensate(transactionId, context, traceId), traceId).map {
      case Left(e) if e.message.contains("CompensationIgnoredException") =>
        logger.info(s"[TraceID: $traceId] Compensation treated as success (ignored): ${e.message}")
        Right(SagaResult.empty[R]().copy(message = Some(e.message)))
      case other => other
    }

  private def executeWithRetryClassification(
                                              operation: => ParticipantEffect[E, R],
                                              traceId: String
                                            )(implicit ec: ExecutionContext): ParticipantEffect[RetryableOrNotException, R] = {
    logger.debug(s"[TraceID: $traceId] SagaParticipant is executing...")

    operation.transform {
      case Success(Right(r)) =>
        logger.info(s"[TraceID: $traceId] SagaParticipant executed successfully with right result")
        Success(Right(r))
      case Success(Left(e: Throwable)) =>
        logger.warn(s"[TraceID: $traceId] SagaParticipant executed failed with $e")
        Success(Left(classify(e)))
      case Success(Left(e)) =>
        logger.warn(s"[TraceID: $traceId] SagaParticipant executed failed with $e")
        Success(Left(classify(new Exception(s"Operation failed: $e"))))
      case Failure(e) =>
        logger.warn(s"[TraceID: $traceId] SagaParticipant executed failed with $e")
        Success(Left(classify(e)))
    }
  }

  def classify(e: Throwable): RetryableOrNotException = {
    val retryableOrNotException = customClassification
      .orElse(defaultClassification)
      .orElse(fallbackClassification)
      .apply(e)

    logger.warn(s"$e had been classified as $retryableOrNotException")

    retryableOrNotException
  }

  private def defaultClassification: PartialFunction[Throwable, RetryableOrNotException] = {
    case _: TimeoutException => RetryableFailure("Operation timed out")
    case _: ConnectException => RetryableFailure("Connection failed")
    case _: SQLTransientException => RetryableFailure("Transient database error")
    case _: IllegalArgumentException => NonRetryableFailure("Invalid argument")
    case _: akka.pattern.CircuitBreakerOpenException => RetryableFailure("Circuit breaker is open")
  }

  protected def customClassification: PartialFunction[Throwable, RetryableOrNotException]

  private def fallbackClassification: PartialFunction[Throwable, RetryableOrNotException] = {
    case e: RetryableOrNotException => e
    case e => NonRetryableFailure("Unclassified error: " + e.getClass.getName + ":" + e.getMessage)
  }

}
