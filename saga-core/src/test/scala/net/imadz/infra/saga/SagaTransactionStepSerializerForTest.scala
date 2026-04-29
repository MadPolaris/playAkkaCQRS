package net.imadz.infra.saga

import java.nio.ByteBuffer
import akka.serialization.Serializer
import net.imadz.common.serialization.PrimitiveConverter
import scala.util.{Failure, Success, Try}

class SagaTransactionStepSerializerForTest extends Serializer {
  override def identifier: Int = 9999
  override def includeManifest: Boolean = true

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case step: SagaTransactionStep[_, _, _] =>
      val stepIdBytes = step.stepId.getBytes("UTF-8")
      val participantClassName = step.participant.getClass.getName.getBytes("UTF-8")
      val buffer = ByteBuffer.allocate(4 + stepIdBytes.length + 4 + participantClassName.length + 4 + 8 + 1)
      
      buffer.putInt(stepIdBytes.length)
      buffer.put(stepIdBytes)
      
      buffer.putInt(participantClassName.length)
      buffer.put(participantClassName)
      
      val phaseInt = step.phase match {
        case SagaPhase.PreparePhase => 0
        case SagaPhase.CommitPhase => 1
        case SagaPhase.CompensatePhase => 2
      }
      buffer.putInt(phaseInt)
      buffer.putLong(step.timeoutDuration.toMillis)
      buffer.put((if (step.retryWhenRecoveredOngoing) 1 else 0).toByte)
      
      buffer.array()
    case _ => throw new IllegalArgumentException(s"Unsupported object type: ${o.getClass.getName}")
  }

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    val buffer = ByteBuffer.wrap(bytes)
    
    val stepIdLen = buffer.getInt()
    val stepIdBytes = new Array[Byte](stepIdLen)
    buffer.get(stepIdBytes)
    val stepId = new String(stepIdBytes, "UTF-8")
    
    val participantClassNameLen = buffer.getInt()
    val participantClassNameBytes = new Array[Byte](participantClassNameLen)
    buffer.get(participantClassNameBytes)
    val participantClassName = new String(participantClassNameBytes, "UTF-8")
    
    val phaseInt = buffer.getInt()
    val phase = phaseInt match {
      case 0 => SagaPhase.PreparePhase
      case 1 => SagaPhase.CommitPhase
      case 2 => SagaPhase.CompensatePhase
    }
    
    val timeoutMillis = buffer.getLong()
    val retryWhenRecoveredOngoing = buffer.get() == 1
    
    val participant = participantClassName match {
      case name if name.contains("SuccessfulParticipant") => SuccessfulParticipant
      case name if name.contains("AlwaysFailingParticipant") => AlwaysFailingParticipant
      case name if name.contains("NonRetryableFailingParticipant") => NonRetryableFailingParticipant
      case name if name.contains("RetryingParticipant") => RetryingParticipant()
      case name if name.contains("TimeoutParticipant") => TimeoutParticipant()
      case name if name.contains("CircuitBreakerParticipant") => CircuitBreakerParticipant()
      case _ => SuccessfulParticipant // Fallback
    }
    
    SagaTransactionStep(
      stepId = stepId,
      phase = phase,
      participant = participant.asInstanceOf[SagaParticipant[Any, Any, Any]],
      maxRetries = 5,
      timeoutDuration = scala.concurrent.duration.Duration(timeoutMillis, "millis"),
      retryWhenRecoveredOngoing = retryWhenRecoveredOngoing
    )
  }
}
