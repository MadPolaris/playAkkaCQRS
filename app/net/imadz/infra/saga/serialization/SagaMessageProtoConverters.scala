package net.imadz.infra.saga.serialization.converters

import akka.serialization.Serializers
import com.google.protobuf.ByteString
import net.imadz.infra.saga.StepExecutor.OperationResponse
import net.imadz.infra.saga.proto.saga_v2._
// [关键] 引入 ScalaPB 生成的 OneOf 辅助类，匹配您现有的 Proto 结构 (Result.Succeed / Result.Error)
import net.imadz.infra.saga.proto.saga_v2.OperationResponseCommandPO.Result.{Error, Succeed}
import net.imadz.infra.saga.serialization.SagaExecutorConverter

/**
 * SagaMessageProtoConverters
 * 适配现有 Proto：OperationResponseCommandPO (oneof result: succeed/error)
 */
trait SagaMessageProtoConverters extends SagaExecutorConverter {

  object OperationResponseConv {

    def toProto(domain: OperationResponse[_, _, _]): OperationResponseCommandPO = {
      val resultOneOf = domain.result match {
        case Right(result) =>
          // 序列化泛型结果 R
          val resultRef = result.asInstanceOf[AnyRef]
          val serializer = serialization.findSerializerFor(resultRef)
          val bytes = serializer.toBinary(resultRef)
          val manifest = Serializers.manifestFor(serializer, resultRef)

          // [适配] 使用 OperationSucceedCommandPO
          Succeed(OperationSucceedCommandPO(
            successMessageType = manifest, // Proto 字段名: successMessageType
            success = ByteString.copyFrom(bytes)
          ))

        case Left(error) =>
          // [适配] 使用 OperationFailedCommandPO
          Error(OperationFailedCommandPO(
            error = Some(RetryableOrNotExceptionConv.toProto(error))
          ))
      }

      // 返回 Wrapper
      OperationResponseCommandPO(result = resultOneOf)
    }

    def fromProto(wrapper: OperationResponseCommandPO): OperationResponse[Any, Any, Any] = {
      wrapper.result match {
        case Succeed(p) => // p 是 OperationSucceedCommandPO
          val result = if (p.success.isEmpty) null else {
            serialization.deserialize(
              p.success.toByteArray,
              serialization.serializerFor(classOf[Any]).identifier,
              p.successMessageType // Proto 字段名: successMessageType
            ).get
          }
          OperationResponse(Right(result), None)

        case Error(p) => // p 是 OperationFailedCommandPO
          val errPO = p.error.getOrElse(RetryableOrNotExceptionPO.defaultInstance)
          val error = RetryableOrNotExceptionConv.fromProto(errPO)
          OperationResponse(Left(error), None)

        case OperationResponseCommandPO.Result.Empty =>
          throw new IllegalArgumentException("OperationResponseCommandPO is empty!")
      }
    }
  }
}