package net.imadz.infra.saga

import akka.serialization.Serializer
import net.imadz.infra.saga.SagaPhase._

import scala.concurrent.duration.DurationLong

class SagaTransactionStepSerializer extends Serializer {
  override def identifier: Int = 1234 // Unique identifier for this serializer

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case step: SagaTransactionStep[_, _] =>
     proto.saga_v2.SagaTransactionStepPO(
        stepId = step.stepId,
        phase = step.phase match {
          case PreparePhase =>proto.saga_v2.TransactionPhasePO.PREPARE_PHASE
          case CommitPhase =>proto.saga_v2.TransactionPhasePO.COMMIT_PHASE
          case CompensatePhase =>proto.saga_v2.TransactionPhasePO.COMPENSATE_PHASE
        },
        maxRetries = step.maxRetries,
        timeoutDurationMillis = step.timeoutDuration.toMillis,
        retryWhenRecoveredOngoing = step.retryWhenRecoveredOngoing,
        participantType = step.participant.getClass.getName
      ).toByteArray
    case _ => throw new IllegalArgumentException(s"Cannot serialize object of type ${o.getClass}")
  }

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    val protoStep =proto.saga_v2.SagaTransactionStepPO.parseFrom(bytes)
    SagaTransactionStep(
      stepId = protoStep.stepId,
      phase = protoStep.phase match {
        case proto.saga_v2.TransactionPhasePO.PREPARE_PHASE => PreparePhase
        case proto.saga_v2.TransactionPhasePO.COMMIT_PHASE => CommitPhase
        case proto.saga_v2.TransactionPhasePO.COMPENSATE_PHASE => CompensatePhase
        case _ => throw new IllegalArgumentException(s"Unknown phase: ${protoStep.phase}")
      },
      participant = createParticipant(protoStep.participantType),
      maxRetries = protoStep.maxRetries,
      timeoutDuration = protoStep.timeoutDurationMillis.millis,
      retryWhenRecoveredOngoing = protoStep.retryWhenRecoveredOngoing
    )
  }

  private def createParticipant(participantType: String): SagaParticipant[_, _] = {
    // This method should create and return the appropriate SagaParticipant based on the type
    // You'll need to implement this based on your specific participants
    participantType match {
      case "net.imadz.infra.saga.SuccessfulParticipant" => SuccessfulParticipant
      case "net.imadz.infra.saga.RetryingParticipant" => RetryingParticipant() // Default value, adjust as needed
      case "net.imadz.infra.saga.TimeoutParticipant" => TimeoutParticipant
      case "net.imadz.infra.saga.AlwaysFailingParticipant" => AlwaysFailingParticipant
      case "net.imadz.infra.saga.NonRetryableFailingParticipant" => NonRetryableFailingParticipant

      case name =>
        println(name)
        throw new IllegalArgumentException(s"Unknown participant type: $participantType")
    }
  }

  override def includeManifest: Boolean = false
}

// Implement similar serializers for other classes (State, RetryableOrNotException, etc.)