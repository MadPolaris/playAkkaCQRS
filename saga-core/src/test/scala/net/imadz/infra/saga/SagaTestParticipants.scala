package net.imadz.infra.saga

import akka.actor.typed.ActorRef
import net.imadz.common.CborSerializable
import net.imadz.infra.saga.SagaParticipant.{ParticipantEffect, RetryableFailure, SagaResult, RetryableOrNotException}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.Future

case object SuccessfulParticipant extends SagaParticipant[String, String, Any] with CborSerializable {
  override def doPrepare(transactionId: String, context: Any, traceId: String) = Future.successful(Right[String, SagaResult[String]](SagaResult("Prepared")))
  override def doCommit(transactionId: String, context: Any, traceId: String) = Future.successful(Right[String, SagaResult[String]](SagaResult("Committed")))
  override def doCompensate(transactionId: String, context: Any, traceId: String) = Future.successful(Right[String, SagaResult[String]](SagaResult("Compensated")))
  override protected def customClassification: PartialFunction[Throwable, RetryableOrNotException] = PartialFunction.empty
}

case object AlwaysFailingParticipant extends SagaParticipant[String, String, Any] {
  override def doPrepare(transactionId: String, context: Any, traceId: String) = Future.failed(RetryableFailure("Always fails"))
  override def doCommit(transactionId: String, context: Any, traceId: String) = Future.failed(RetryableFailure("Always fails"))
  override def doCompensate(transactionId: String, context: Any, traceId: String) = Future.failed(RetryableFailure("Always fails"))
  override protected def customClassification: PartialFunction[Throwable, RetryableOrNotException] = PartialFunction.empty
}

case object NonRetryableFailingParticipant extends SagaParticipant[String, String, Any] {
  override def doPrepare(transactionId: String, context: Any, traceId: String) = Future.failed(new RuntimeException("Non-retryable"))
  override def doCommit(transactionId: String, context: Any, traceId: String) = Future.successful(Right(SagaResult("Committed")))
  override def doCompensate(transactionId: String, context: Any, traceId: String) = Future.successful(Right(SagaResult("Compensated")))
  override protected def customClassification: PartialFunction[Throwable, RetryableOrNotException] = PartialFunction.empty
}

case class RetryingParticipant() extends SagaParticipant[String, String, Any] with CborSerializable {
  private var attempts = 0
  private val succeedAfter = 3
  
  override protected val logger: Logger = LoggerFactory.getLogger(getClass)

  override def doPrepare(transactionId: String, context: Any, traceId: String) = {
    attempts += 1
    logger.warn(s"[TraceID: $traceId] RetryingParticipant is doing prepare $attempts times")
    if (attempts < succeedAfter) Future.failed(RetryableFailure("Retry needed"))
    else Future.successful(Right[String, SagaResult[String]](SagaResult("Success after retry")))
  }

  override def doCommit(transactionId: String, context: Any, traceId: String) = Future.successful(Right[String, SagaResult[String]](SagaResult("Committed")))
  override def doCompensate(transactionId: String, context: Any, traceId: String) = Future.successful(Right[String, SagaResult[String]](SagaResult("Compensated")))
  override protected def customClassification: PartialFunction[Throwable, RetryableOrNotException] = {
    case it: RetryableFailure => it
  }
}

case class TimeoutParticipant() extends SagaParticipant[String, String, Any] with CborSerializable {
  override def doPrepare(transactionId: String, context: Any, traceId: String) = {
    Future.never
  }
  override def doCommit(transactionId: String, context: Any, traceId: String) = Future.successful(Right(SagaResult("Committed")))
  override def doCompensate(transactionId: String, context: Any, traceId: String) = Future.successful(Right(SagaResult("Compensated")))
  override protected def customClassification: PartialFunction[Throwable, RetryableOrNotException] = PartialFunction.empty
}

case class CircuitBreakerParticipant() extends SagaParticipant[RetryableOrNotException, String, Any] with CborSerializable {
  private var attempts = 0
  override def doPrepare(transactionId: String, context: Any, traceId: String) = {
    attempts += 1
    Future.failed(RetryableFailure(s"Failure attempt $attempts"))
  }
  override def doCommit(transactionId: String, context: Any, traceId: String) = Future.successful(Right(SagaResult("Committed")))
  override def doCompensate(transactionId: String, context: Any, traceId: String) = Future.successful(Right(SagaResult("Compensated")))
  override protected def customClassification: PartialFunction[Throwable, RetryableOrNotException] = {
    case it: RetryableFailure => it
  }
}
