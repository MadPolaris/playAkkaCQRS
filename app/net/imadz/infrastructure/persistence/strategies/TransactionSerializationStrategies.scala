package net.imadz.infrastructure.persistence.strategies

import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.application.services.transactor.{FromAccountParticipant, ToAccountParticipant}
import net.imadz.infra.saga.SagaParticipant
import net.imadz.infra.saga.serialization.SagaParticipantSerializerStrategy
import net.imadz.infrastructure.persistence.converters.SagaProtoConverters
import net.imadz.infrastructure.proto.saga_participant.{FromAccountParticipantPO, ToAccountParticipantPO}

import scala.concurrent.ExecutionContext

object TransactionSerializationStrategies {

  case class FromAccountStrategy(repository: CreditBalanceRepository)(implicit executionContext: ExecutionContext) extends SagaParticipantSerializerStrategy with SagaProtoConverters {
    override def manifest: String = "FromAccountParticipantPO"

    override def participantClass: Class[_] = classOf[FromAccountParticipant]

    private val fromConv: FromAccountParticipantConv = FromAccountParticipantConv(repository)

    override def toBinary(participant: SagaParticipant[_, _]): Array[Byte] = {
      val p = participant.asInstanceOf[FromAccountParticipant]
      fromConv.toProto(p).toByteArray
    }

    // [关键] 按需获取 CreditBalanceRepository
    override def fromBinary(bytes: Array[Byte]): SagaParticipant[_, _] = {
      val po = FromAccountParticipantPO.parseFrom(bytes)
      fromConv.fromProto(po)
    }

  }


  case class ToAccountStrategy(repository: CreditBalanceRepository)(implicit executionContext: ExecutionContext) extends SagaParticipantSerializerStrategy with SagaProtoConverters {
    override def manifest: String = "ToAccountParticipantPO"

    override def participantClass: Class[_] = classOf[ToAccountParticipant]

    private val toConv: ToAccountParticipantConv = ToAccountParticipantConv(repository)

    override def toBinary(participant: SagaParticipant[_, _]): Array[Byte] = {
      val p = participant.asInstanceOf[ToAccountParticipant]
      toConv.toProto(p).toByteArray
    }

    // [关键] 按需获取 CreditBalanceRepository
    override def fromBinary(bytes: Array[Byte]): SagaParticipant[_, _] = {
      val po = ToAccountParticipantPO.parseFrom(bytes)
      toConv.fromProto(po)
    }

  }


}