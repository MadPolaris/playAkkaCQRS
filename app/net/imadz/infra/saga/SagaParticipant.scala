package net.imadz.infra.saga

import com.fasterxml.jackson.core.{JsonGenerator, JsonParser}
import com.fasterxml.jackson.databind.{DeserializationContext, JsonDeserializer, JsonSerializer, SerializerProvider}
import net.imadz.infra.saga.SagaParticipant._
import org.slf4j.{Logger, LoggerFactory}

import java.net.ConnectException
import java.sql.SQLTransientException
import scala.concurrent.{ExecutionContext, Future, TimeoutException}
import scala.util.{Failure, Success}

object SagaParticipant {
  type ParticipantEffect[E, R] = Future[Either[E, R]]

  sealed trait RetryableOrNotException {
    def message: String
  }

  case class RetryableFailure(message: String) extends RuntimeException(message) with RetryableOrNotException

  case class NonRetryableFailure(message: String) extends RuntimeException(message) with RetryableOrNotException

}

trait SagaParticipant[E, R] {

  protected def logger: Logger = LoggerFactory.getLogger(getClass)

  protected def doPrepare(transactionId: String): ParticipantEffect[E, R]

  protected def doCommit(transactionId: String): ParticipantEffect[E, R]

  protected def doCompensate(transactionId: String): ParticipantEffect[E, R]

  def prepare(transactionId: String)(implicit ec: ExecutionContext): ParticipantEffect[RetryableOrNotException, R] =
    executeWithRetryClassification(doPrepare(transactionId))

  def commit(transactionId: String)(implicit ec: ExecutionContext): ParticipantEffect[RetryableOrNotException, R] =
    executeWithRetryClassification(doCommit(transactionId))

  def compensate(transactionId: String)(implicit ec: ExecutionContext): ParticipantEffect[RetryableOrNotException, R] =
    executeWithRetryClassification(doCompensate(transactionId))

  private def executeWithRetryClassification(
                                              operation: => ParticipantEffect[E, R]
                                            )(implicit ec: ExecutionContext): ParticipantEffect[RetryableOrNotException, R] = {
    logger.debug("SagaParticipant is executing...")

    operation.transform {
      case Success(Right(r)) =>
        logger.info("SagaParticipant executed successfully with right result")
        Success(Right(r))
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
    case e => NonRetryableFailure("Unclassified error: " + e.getMessage)
  }

}
