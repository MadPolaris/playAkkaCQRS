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

}

trait SagaParticipant[E, R, C] {

  protected def logger: Logger = LoggerFactory.getLogger(getClass)

  protected def doPrepare(transactionId: String, context: C): ParticipantEffect[E, R]

  protected def doCommit(transactionId: String, context: C): ParticipantEffect[E, R]

  protected def doCompensate(transactionId: String, context: C): ParticipantEffect[E, R]

  def prepare(transactionId: String, context: C)(implicit ec: ExecutionContext): ParticipantEffect[RetryableOrNotException, R] =
    executeWithRetryClassification(doPrepare(transactionId, context))

  def commit(transactionId: String, context: C)(implicit ec: ExecutionContext): ParticipantEffect[RetryableOrNotException, R] =
    executeWithRetryClassification(doCommit(transactionId, context))

  def compensate(transactionId: String, context: C)(implicit ec: ExecutionContext): ParticipantEffect[RetryableOrNotException, R] =
    executeWithRetryClassification(doCompensate(transactionId, context))

  private def executeWithRetryClassification(
                                              operation: => ParticipantEffect[E, R]
                                            )(implicit ec: ExecutionContext): ParticipantEffect[RetryableOrNotException, R] = {
    logger.debug("SagaParticipant is executing...")

    operation.transform {
      case Success(Right(r)) =>
        logger.info("SagaParticipant executed successfully with right result")
        Success(Right(r))
      case Success(Left(e@net.imadz.common.CommonTypes.iMadzError(code, message))) =>
        logger.warn(s"SagaParticipant executed failed with $e")
        Success(Left(classifyFailure(e)))
      case Success(Left(e)) =>
        logger.warn(s"SagaParticipant executed failed with $e")
        Success(Left(classifyFailure(new Exception("Operation failed"))))
      case Failure(e) =>
        logger.warn(s"SagaParticipant executed failed with $e")
        Success(Left(classifyFailure(e)))
    }
  }

  private def classifyFailure(e: Throwable): RetryableOrNotException = {
    val retryableOrNotException = defaultClassification
      .orElse(customClassification)
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
  }

  protected def customClassification: PartialFunction[Throwable, RetryableOrNotException]

  private def fallbackClassification: PartialFunction[Throwable, RetryableOrNotException] = {
    case e => NonRetryableFailure("Unclassified error: " + e.getClass.getName + ":" + e.getMessage)
  }

}
