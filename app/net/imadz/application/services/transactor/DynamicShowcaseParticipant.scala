package net.imadz.application.services.transactor

import net.imadz.infra.saga.SagaParticipant.{NonRetryableFailure, ParticipantEffect, RetryableFailure, SagaResult, RetryableOrNotException}
import net.imadz.infra.saga.{SagaParticipant, SagaPhase}
import org.slf4j.LoggerFactory
import java.util.concurrent.{Executors, TimeUnit, ConcurrentHashMap}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._

object DynamicShowcaseParticipant {
  sealed trait Behavior
  case object Success extends Behavior
  case object FailRetryable extends Behavior
  case object FailNonRetryable extends Behavior
  case object Timeout extends Behavior
  case object FailTwiceThenSucceed extends Behavior

  private val behaviors = new ConcurrentHashMap[String, Behavior]()
  // 核心计数器：Key 是 transactionId + stepId + phase
  private val attemptCounters = new ConcurrentHashMap[String, Int]()

  def setBehavior(stepId: String, behavior: Behavior): Unit = {
    behaviors.put(stepId, behavior)
  }

  def getBehavior(stepId: String): Behavior = {
    Option(behaviors.get(stepId)).getOrElse(Success)
  }

  def resetAttempts(transactionId: String): Unit = {
    // 可以在新事务开始时清理，或者简单起见不清理，因为 key 包含 transactionId
  }

  private val scheduler = Executors.newScheduledThreadPool(1)
  
  def delay[T](duration: FiniteDuration)(block: => T): Future[T] = {
    val promise = Promise[T]()
    scheduler.schedule(new Runnable {
      override def run(): Unit = promise.success(block)
    }, duration.toMillis, TimeUnit.MILLISECONDS)
    promise.future
  }
}

class DynamicShowcaseParticipant(val participantId: String) extends SagaParticipant[Any, String, Any] {
  import DynamicShowcaseParticipant._

  override protected val logger = LoggerFactory.getLogger(getClass)

  override protected def doPrepare(transactionId: String, context: Any, traceId: String): ParticipantEffect[Any, String] = 
    execute(transactionId, "prepare", traceId)

  override protected def doCommit(transactionId: String, context: Any, traceId: String): ParticipantEffect[Any, String] = 
    execute(transactionId, "commit", traceId)

  override protected def doCompensate(transactionId: String, context: Any, traceId: String): ParticipantEffect[Any, String] = 
    execute(transactionId, "compensate", traceId)

  private def execute(transactionId: String, phase: String, traceId: String): ParticipantEffect[Any, String] = {
    val behavior = getBehavior(participantId)
    val counterKey = s"$transactionId-$participantId-$phase"
    val currentAttempt = attemptCounters.compute(counterKey, (_, v) => if (v == null) 1 else v + 1)

    logger.info(s"[TraceID: $traceId] Step $participantId ($phase) execution attempt: $currentAttempt, Behavior: $behavior")
    
    behavior match {
      case Success => 
        delay(1.second)(Right(SagaResult(s"$participantId-$phase-success")))
      
      case FailTwiceThenSucceed =>
        if (currentAttempt <= 2) {
          logger.warn(s"[TraceID: $traceId] Step $participantId ($phase) failing intentionally (attempt $currentAttempt/2)")
          // Use Future.failed with specific message that our new classification understands
          Future.failed(new Exception(s"RetryableFailure: Simulated transient error (attempt $currentAttempt)"))
        } else {
          logger.info(s"[TraceID: $traceId] Step $participantId ($phase) succeeding after $currentAttempt attempts")
          delay(1.second)(Right(SagaResult(s"$participantId-$phase-healed")))
        }

      case FailRetryable => 
        Future.failed(new Exception("RetryableFailure: Manual retryable error"))
      case FailNonRetryable => 
        Future.failed(new Exception("NonRetryable: Manual non-retryable error"))
      case Timeout => 
        delay(10.seconds)(Right(SagaResult("Timeout simulated")))
    }
  }

  override protected def customClassification: PartialFunction[Throwable, RetryableOrNotException] = {
    case e: RuntimeException => NonRetryableFailure(e.getMessage)
  }
}

object ShowcaseStrategy extends net.imadz.infra.saga.serialization.SagaParticipantSerializerStrategy {
  override def participantClass: Class[_] = classOf[DynamicShowcaseParticipant]
  override def manifest: String = "DynamicShowcaseParticipant"
  override def toBinary(participant: net.imadz.infra.saga.SagaParticipant[_, _, _]): Array[Byte] = 
    participant.asInstanceOf[DynamicShowcaseParticipant].participantId.getBytes("UTF-8")
  override def fromBinary(bytes: Array[Byte]): net.imadz.infra.saga.SagaParticipant[_, _, _] = 
    new DynamicShowcaseParticipant(new String(bytes, "UTF-8"))
}
