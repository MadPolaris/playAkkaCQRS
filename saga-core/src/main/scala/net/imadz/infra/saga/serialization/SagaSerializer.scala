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
      // 这里返回的是 GeneratedMessage (SucceededPO 或 FailedPO)
      OperationResponseConv.toProto(response).toByteArray
    case _ =>
      throw new IllegalArgumentException(s"Cannot serialize object of type ${o.getClass}")
  }

  // 确认一下 SagaSerializer 的 fromBinary 逻辑是否匹配
  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    // 直接解析最外层 Wrapper
    val wrapper = OperationResponseCommandPO.parseFrom(bytes)
    // 交给 Converter 拆包
    OperationResponseConv.fromProto(wrapper)
  }
}