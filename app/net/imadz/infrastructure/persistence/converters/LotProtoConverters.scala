package net.imadz.infrastructure.persistence.converters

import net.imadz.common.CommonTypes.Id
import net.imadz.common.serialization.PrimitiveConverter
import net.imadz.domain.entities.LotEntity._
import net.imadz.infrastructure.proto.lot._

trait LotProtoConverters extends PrimitiveConverter {

  // Helper: converts Set[Id] <-> WaferIdSet (repeated string)
  object WaferIdSetConv extends ProtoConverter[Set[Id], WaferIdSet] {
    override def toProto(ids: Set[Id]): WaferIdSet = WaferIdSet(ids.map(IdConv.toProto).toSeq)
    override def fromProto(p: WaferIdSet): Set[Id] = p.waferIds.map(IdConv.fromProto).toSet
  }

  // --- Events ---

  object LotCreatedConv extends ProtoConverter[LotCreated, LotCreatedPO] {
    override def toProto(e: LotCreated): LotCreatedPO = LotCreatedPO(
      productId = e.productId,
      waferIds = e.waferIds.map(IdConv.toProto).toSeq
    )
    override def fromProto(p: LotCreatedPO): LotCreated = LotCreated(
      productId = p.productId,
      waferIds = p.waferIds.map(IdConv.fromProto).toSet
    )
  }

  object WaferRemovalReservedConv extends ProtoConverter[WaferRemovalReserved, WaferRemovalReservedPO] {
    override def toProto(e: WaferRemovalReserved): WaferRemovalReservedPO = WaferRemovalReservedPO(
      transferId = IdConv.toProto(e.transferId),
      waferIds = e.waferIds.map(IdConv.toProto).toSeq
    )
    override def fromProto(p: WaferRemovalReservedPO): WaferRemovalReserved = WaferRemovalReserved(
      transferId = IdConv.fromProto(p.transferId),
      waferIds = p.waferIds.map(IdConv.fromProto).toSet
    )
  }

  object WaferRemovalCommittedConv extends ProtoConverter[WaferRemovalCommitted, WaferRemovalCommittedPO] {
    override def toProto(e: WaferRemovalCommitted): WaferRemovalCommittedPO = WaferRemovalCommittedPO(
      transferId = IdConv.toProto(e.transferId)
    )
    override def fromProto(p: WaferRemovalCommittedPO): WaferRemovalCommitted = WaferRemovalCommitted(
      transferId = IdConv.fromProto(p.transferId)
    )
  }

  object WaferRemovalReleasedConv extends ProtoConverter[WaferRemovalReleased, WaferRemovalReleasedPO] {
    override def toProto(e: WaferRemovalReleased): WaferRemovalReleasedPO = WaferRemovalReleasedPO(
      transferId = IdConv.toProto(e.transferId)
    )
    override def fromProto(p: WaferRemovalReleasedPO): WaferRemovalReleased = WaferRemovalReleased(
      transferId = IdConv.fromProto(p.transferId)
    )
  }

  object WaferAdditionReservedConv extends ProtoConverter[WaferAdditionReserved, WaferAdditionReservedPO] {
    override def toProto(e: WaferAdditionReserved): WaferAdditionReservedPO = WaferAdditionReservedPO(
      transferId = IdConv.toProto(e.transferId),
      waferIds = e.waferIds.map(IdConv.toProto).toSeq
    )
    override def fromProto(p: WaferAdditionReservedPO): WaferAdditionReserved = WaferAdditionReserved(
      transferId = IdConv.fromProto(p.transferId),
      waferIds = p.waferIds.map(IdConv.fromProto).toSet
    )
  }

  object WaferAdditionCommittedConv extends ProtoConverter[WaferAdditionCommitted, WaferAdditionCommittedPO] {
    override def toProto(e: WaferAdditionCommitted): WaferAdditionCommittedPO = WaferAdditionCommittedPO(
      transferId = IdConv.toProto(e.transferId)
    )
    override def fromProto(p: WaferAdditionCommittedPO): WaferAdditionCommitted = WaferAdditionCommitted(
      transferId = IdConv.fromProto(p.transferId)
    )
  }

  object WaferAdditionCanceledConv extends ProtoConverter[WaferAdditionCanceled, WaferAdditionCanceledPO] {
    override def toProto(e: WaferAdditionCanceled): WaferAdditionCanceledPO = WaferAdditionCanceledPO(
      transferId = IdConv.toProto(e.transferId)
    )
    override def fromProto(p: WaferAdditionCanceledPO): WaferAdditionCanceled = WaferAdditionCanceled(
      transferId = IdConv.fromProto(p.transferId)
    )
  }

  object PhaseStartedConv extends ProtoConverter[PhaseStarted, PhaseStartedPO] {
    override def toProto(e: PhaseStarted): PhaseStartedPO = PhaseStartedPO(phaseId = e.phaseId)
    override def fromProto(p: PhaseStartedPO): PhaseStarted = PhaseStarted(p.phaseId)
  }

  object PhaseCompletedConv extends ProtoConverter[PhaseCompleted, PhaseCompletedPO] {
    override def toProto(e: PhaseCompleted): PhaseCompletedPO = PhaseCompletedPO(phaseId = e.phaseId)
    override def fromProto(p: PhaseCompletedPO): PhaseCompleted = PhaseCompleted(p.phaseId)
  }

  object LotSealedConv extends ProtoConverter[LotSealed, LotSealedPO] {
    override def toProto(e: LotSealed): LotSealedPO = LotSealedPO()
    override def fromProto(p: LotSealedPO): LotSealed = LotSealed()
  }

  // --- State Snapshot ---

  object LotStateConv extends ProtoConverter[LotState, LotStatePO] {
    override def toProto(s: LotState): LotStatePO = LotStatePO(
      lotId = IdConv.toProto(s.lotId),
      productId = s.productId,
      waferIds = s.waferIds.map(IdConv.toProto).toSeq,
      reservedWafers = toProtoMap(s.reservedWafers, IdConv, WaferIdSetConv),
      incomingWafers = toProtoMap(s.incomingWafers, IdConv, WaferIdSetConv),
      phase = s.phase.toString
    )

    override def fromProto(p: LotStatePO): LotState = LotState(
      lotId = IdConv.fromProto(p.lotId),
      productId = p.productId,
      waferIds = p.waferIds.map(IdConv.fromProto).toSet,
      reservedWafers = fromProtoMap(p.reservedWafers, IdConv, WaferIdSetConv),
      incomingWafers = fromProtoMap(p.incomingWafers, IdConv, WaferIdSetConv),
      phase = parsePhase(p.phase)
    )

    private def parsePhase(s: String): LotPhase = s match {
      case "Empty" => Empty
      case "Active" => Active
      case "Sealed" => Sealed
      case "Completed" => Completed
      case _ => Empty
    }
  }
}
