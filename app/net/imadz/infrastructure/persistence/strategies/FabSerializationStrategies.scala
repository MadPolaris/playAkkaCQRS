package net.imadz.infrastructure.persistence.strategies

import net.imadz.application.aggregates.repository.{LotRepository, WaferRepository}
import net.imadz.application.services.transactor.{SourceLotParticipant, TargetLotParticipant, WaferTransferParticipant}
import net.imadz.infra.saga.SagaParticipant
import net.imadz.infra.saga.serialization.SagaParticipantSerializerStrategy
import net.imadz.infrastructure.persistence.converters.FabSagaProtoConverters
import net.imadz.infrastructure.proto.fab_saga_participant.{SourceLotParticipantPO, TargetLotParticipantPO, WaferTransferParticipantPO}

import scala.concurrent.ExecutionContext

object FabSerializationStrategies {

  case class SourceLotStrategy(lotRepository: LotRepository)(implicit executionContext: ExecutionContext)
    extends SagaParticipantSerializerStrategy with FabSagaProtoConverters {

    override def manifest: String = "SourceLotParticipantPO"
    override def participantClass: Class[_] = classOf[SourceLotParticipant]

    private val conv: SourceLotParticipantConv = SourceLotParticipantConv(lotRepository)

    override def toBinary(participant: SagaParticipant[_, _, _]): Array[Byte] = {
      val p = participant.asInstanceOf[SourceLotParticipant]
      conv.toProto(p).toByteArray
    }

    override def fromBinary(bytes: Array[Byte]): SagaParticipant[_, _, _] = {
      val po = SourceLotParticipantPO.parseFrom(bytes)
      conv.fromProto(po)
    }
  }

  case class TargetLotStrategy(lotRepository: LotRepository)(implicit executionContext: ExecutionContext)
    extends SagaParticipantSerializerStrategy with FabSagaProtoConverters {

    override def manifest: String = "TargetLotParticipantPO"
    override def participantClass: Class[_] = classOf[TargetLotParticipant]

    private val conv: TargetLotParticipantConv = TargetLotParticipantConv(lotRepository)

    override def toBinary(participant: SagaParticipant[_, _, _]): Array[Byte] = {
      val p = participant.asInstanceOf[TargetLotParticipant]
      conv.toProto(p).toByteArray
    }

    override def fromBinary(bytes: Array[Byte]): SagaParticipant[_, _, _] = {
      val po = TargetLotParticipantPO.parseFrom(bytes)
      conv.fromProto(po)
    }
  }

  case class WaferTransferStrategy(waferRepository: WaferRepository)(implicit executionContext: ExecutionContext)
    extends SagaParticipantSerializerStrategy with FabSagaProtoConverters {

    override def manifest: String = "WaferTransferParticipantPO"
    override def participantClass: Class[_] = classOf[WaferTransferParticipant]

    private val conv: WaferTransferParticipantConv = WaferTransferParticipantConv(waferRepository)

    override def toBinary(participant: SagaParticipant[_, _, _]): Array[Byte] = {
      val p = participant.asInstanceOf[WaferTransferParticipant]
      conv.toProto(p).toByteArray
    }

    override def fromBinary(bytes: Array[Byte]): SagaParticipant[_, _, _] = {
      val po = WaferTransferParticipantPO.parseFrom(bytes)
      conv.fromProto(po)
    }
  }
}
