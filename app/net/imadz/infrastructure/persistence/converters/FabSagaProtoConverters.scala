package net.imadz.infrastructure.persistence.converters

import net.imadz.application.aggregates.repository.{LotRepository, WaferRepository}
import net.imadz.application.services.transactor.{SourceLotParticipant, TargetLotParticipant, WaferTransferParticipant}
import net.imadz.common.serialization.PrimitiveConverter
import net.imadz.infrastructure.proto.fab_saga_participant.{SourceLotParticipantPO, TargetLotParticipantPO, WaferTransferParticipantPO}

import scala.concurrent.ExecutionContext

trait FabSagaProtoConverters extends PrimitiveConverter {

  case class SourceLotParticipantConv(lotRepository: LotRepository)(implicit ec: ExecutionContext) extends ProtoConverter[SourceLotParticipant, SourceLotParticipantPO] {
    override def toProto(d: SourceLotParticipant): SourceLotParticipantPO = SourceLotParticipantPO(
      sourceLotId = IdConv.toProto(d.sourceLotId),
      waferIds = d.waferIds.map(IdConv.toProto).toSeq
    )
    override def fromProto(p: SourceLotParticipantPO): SourceLotParticipant = SourceLotParticipant(
      sourceLotId = IdConv.fromProto(p.sourceLotId),
      waferIds = p.waferIds.map(IdConv.fromProto).toSet
    )
  }

  case class TargetLotParticipantConv(lotRepository: LotRepository)(implicit ec: ExecutionContext) extends ProtoConverter[TargetLotParticipant, TargetLotParticipantPO] {
    override def toProto(d: TargetLotParticipant): TargetLotParticipantPO = TargetLotParticipantPO(
      targetLotId = IdConv.toProto(d.targetLotId),
      waferIds = d.waferIds.map(IdConv.toProto).toSeq
    )
    override def fromProto(p: TargetLotParticipantPO): TargetLotParticipant = TargetLotParticipant(
      targetLotId = IdConv.fromProto(p.targetLotId),
      waferIds = p.waferIds.map(IdConv.fromProto).toSet
    )
  }

  case class WaferTransferParticipantConv(waferRepository: WaferRepository)(implicit ec: ExecutionContext) extends ProtoConverter[WaferTransferParticipant, WaferTransferParticipantPO] {
    override def toProto(d: WaferTransferParticipant): WaferTransferParticipantPO = WaferTransferParticipantPO(
      waferId = IdConv.toProto(d.waferId),
      targetLotId = IdConv.toProto(d.targetLotId)
    )
    override def fromProto(p: WaferTransferParticipantPO): WaferTransferParticipant = WaferTransferParticipant(
      waferId = IdConv.fromProto(p.waferId),
      targetLotId = IdConv.fromProto(p.targetLotId)
    )
  }
}
