package net.imadz.infra.saga.serialization

import akka.actor.ExtendedActorSystem
import akka.serialization.BaseSerializer
import net.imadz.common.serialization.SerializationExtension
import net.imadz.infra.saga.StepExecutor.OperationResponse
import net.imadz.infra.saga.proto.saga_v2.OperationResponseCommandPO
import net.imadz.infra.saga.serialization.converters.SagaMessageProtoConverters

class SagaSerializer(override val system: ExtendedActorSystem)
  extends BaseSerializer
    with SagaMessageProtoConverters {

  lazy val serializationExtension: SerializationExtension = SerializationExtension(system)
  override val identifier: Int = 1235

  override def includeManifest: Boolean = true

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case response: OperationResponse[_, _, _] =>
      // This returns a GeneratedMessage (SucceededPO or FailedPO)
      OperationResponseConv.toProto(response).toByteArray
    case _ =>
      throw new IllegalArgumentException(s"Cannot serialize object of type ${o.getClass}")
  }

  // Confirm if the fromBinary logic of SagaSerializer matches
  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    // Directly parse the outermost Wrapper
    val wrapper = OperationResponseCommandPO.parseFrom(bytes)
    // Hand over to Converter to unpack
    OperationResponseConv.fromProto(wrapper)
  }
}