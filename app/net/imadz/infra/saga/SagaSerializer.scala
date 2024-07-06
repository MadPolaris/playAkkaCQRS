package net.imadz.infra.saga

import akka.actor.ExtendedActorSystem
import akka.serialization.{SerializationExtension, SerializerWithStringManifest}
import com.google.protobuf.ByteString
import net.imadz.infra.saga.StepExecutor.{OperationResponse, OperationSucceeded}
import net.imadz.infra.saga.proto.saga_v2.{OperationFailedCommandPO, OperationSucceedCommandPO, OperationSucceededPO, RetryableOrNotExceptionPO}

class SagaSerializer(system: ExtendedActorSystem) extends SerializerWithStringManifest {

  private lazy val serialization = SerializationExtension(system)

  override def identifier: Int = 1000

  override def manifest(o: AnyRef): String = o.getClass.getName

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case OperationResponse(result, replyTo) =>
      result match {
        case Left(SagaParticipant.RetryableFailure(message)) =>
          OperationFailedCommandPO(Some(RetryableOrNotExceptionPO(isRetryable = true, message))).toByteArray
        case Left(SagaParticipant.NonRetryableFailure(message)) =>
          OperationFailedCommandPO(Some(RetryableOrNotExceptionPO(isRetryable = false, message = message))).toByteArray
        case Right(positiveResult: AnyRef) =>
          val serializer = serialization.findSerializerFor(positiveResult)
          OperationSucceedCommandPO(
            successMessageType = positiveResult.getClass.getName,
            success = ByteString.copyFrom(serializer.toBinary(positiveResult))
          ).toByteArray
      }

    case OperationSucceeded(result: AnyRef) =>
      val serializer = serialization.findSerializerFor(result)
      OperationSucceededPO(resultType = result.getClass.getName, result = ByteString.copyFrom(serializer.toBinary(result))).toByteArray
    case _ => throw new IllegalArgumentException(s"Cannot serialize ${o.getClass}")
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    manifest match {
      case className if className == classOf[OperationResponse[_, _]].getName =>
        try {
          val failedCommand = OperationFailedCommandPO.parseFrom(bytes)
          failedCommand.error match {
            case Some(exception) if exception.isRetryable =>
              OperationResponse(Left(SagaParticipant.RetryableFailure(exception.message)), None)
            case Some(exception) =>
              OperationResponse(Left(SagaParticipant.NonRetryableFailure(exception.message)), None)
            case None =>
              throw new IllegalArgumentException("Invalid OperationFailedCommandPO: missing exception")
          }
        } catch {
          case _: com.google.protobuf.InvalidProtocolBufferException =>
            // If it's not a failure, it must be a success
            val successCommand = OperationSucceedCommandPO.parseFrom(bytes)
            val resultClass = system.dynamicAccess.getClassFor[AnyRef](successCommand.successMessageType)
              .getOrElse(throw new ClassNotFoundException(s"Cannot find class ${successCommand.successMessageType}"))
            val resultSerializer = serialization.serializerFor(resultClass)
            val result = resultSerializer.fromBinary(successCommand.success.toByteArray, resultClass)
            OperationResponse(Right(result), None) // Note: We cannot reconstruct the ActorRef here
        }

      case className if className == classOf[OperationSucceeded[_]].getName =>
        val protoMessage = OperationSucceededPO.parseFrom(bytes)
        val resultClass = system.dynamicAccess.getClassFor[AnyRef](protoMessage.resultType)
          .getOrElse(throw new ClassNotFoundException(s"Cannot find class ${protoMessage.resultType}"))
        val resultSerializer = serialization.serializerFor(resultClass)
        val result = resultSerializer.fromBinary(protoMessage.result.toByteArray, resultClass)
        OperationSucceeded(result)

      case _ => throw new IllegalArgumentException(s"Cannot deserialize class $manifest")
    }
  }
}
