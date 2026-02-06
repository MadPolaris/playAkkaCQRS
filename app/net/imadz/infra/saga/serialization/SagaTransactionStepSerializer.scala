package net.imadz.infra.saga.serialization

import akka.actor.ExtendedActorSystem
import akka.serialization.Serializer

import scala.util.{Failure, Success, Try}
// 假设这些 Proto 也是框架的一部分
import net.imadz.infra.saga.SagaTransactionStep
import net.imadz.infra.saga.proto.saga_v2.SagaTransactionStepPO

import scala.concurrent.ExecutionContext

class SagaTransactionStepSerializer(override val system: ExtendedActorSystem)
  extends Serializer with SagaExecutorConverter {

  implicit val executionContext: ExecutionContext = system.dispatcher

  override def identifier: Int = 1234

  override def includeManifest: Boolean = false

  def toBinary(o: AnyRef): Array[Byte] = o match {
    case step: SagaTransactionStep[_, _, _] => SagaStepConv.toProto(step).toByteArray
    case _ => throw new IllegalArgumentException(s"Cannot serialize object of type ${o.getClass}")
  }

  def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    Try(SagaTransactionStepPO.parseFrom(bytes)).map(SagaStepConv.fromProto) match {
      case Success(step) => step
      case Failure(e) => throw new RuntimeException(s"Failed to deserialize SagaTransactionStep: ${e.getMessage}")
    }
  }

}