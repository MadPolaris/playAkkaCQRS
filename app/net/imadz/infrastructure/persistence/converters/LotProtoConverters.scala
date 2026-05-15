package net.imadz.infrastructure.persistence.converters

import net.imadz.common.CommonTypes.Id
import net.imadz.common.serialization.PrimitiveConverter
import net.imadz.domain.entities.LotEntity._
import net.imadz.infrastructure.proto.lot._

trait LotProtoConverters extends PrimitiveConverter {

  object WaferIdSetConv extends ProtoConverter[Set[Id], WaferIdSet] {
    override def toProto(ids: Set[Id]): WaferIdSet = WaferIdSet(ids.map(IdConv.toProto).toSeq)
    override def fromProto(p: WaferIdSet): Set[Id] = p.waferIds.map(IdConv.fromProto).toSet
  }

  object WaferClassResultConv extends ProtoConverter[WaferClassResult, WaferClassResultPO] {
    override def toProto(r: WaferClassResult): WaferClassResultPO =
      WaferClassResultPO(waferId = "", classification = r.classification, cdValueNm = r.cdValueNm, reworkCount = r.reworkCount)
    override def fromProto(p: WaferClassResultPO): WaferClassResult =
      WaferClassResult(classification = p.classification, cdValueNm = p.cdValueNm, reworkCount = p.reworkCount)
  }

  // --- Existing Events ---
  object LotCreatedConv extends ProtoConverter[LotCreated, LotCreatedPO] {
    override def toProto(e: LotCreated): LotCreatedPO = LotCreatedPO(productId = e.productId, waferIds = e.waferIds.map(IdConv.toProto).toSeq)
    override def fromProto(p: LotCreatedPO): LotCreated = LotCreated(productId = p.productId, waferIds = p.waferIds.map(IdConv.fromProto).toSet)
  }

  object WaferRemovalReservedConv extends ProtoConverter[WaferRemovalReserved, WaferRemovalReservedPO] {
    override def toProto(e: WaferRemovalReserved): WaferRemovalReservedPO = WaferRemovalReservedPO(transferId = IdConv.toProto(e.transferId), waferIds = e.waferIds.map(IdConv.toProto).toSeq)
    override def fromProto(p: WaferRemovalReservedPO): WaferRemovalReserved = WaferRemovalReserved(transferId = IdConv.fromProto(p.transferId), waferIds = p.waferIds.map(IdConv.fromProto).toSet)
  }

  object WaferRemovalCommittedConv extends ProtoConverter[WaferRemovalCommitted, WaferRemovalCommittedPO] {
    override def toProto(e: WaferRemovalCommitted): WaferRemovalCommittedPO = WaferRemovalCommittedPO(transferId = IdConv.toProto(e.transferId))
    override def fromProto(p: WaferRemovalCommittedPO): WaferRemovalCommitted = WaferRemovalCommitted(transferId = IdConv.fromProto(p.transferId))
  }

  object WaferRemovalReleasedConv extends ProtoConverter[WaferRemovalReleased, WaferRemovalReleasedPO] {
    override def toProto(e: WaferRemovalReleased): WaferRemovalReleasedPO = WaferRemovalReleasedPO(transferId = IdConv.toProto(e.transferId))
    override def fromProto(p: WaferRemovalReleasedPO): WaferRemovalReleased = WaferRemovalReleased(transferId = IdConv.fromProto(p.transferId))
  }

  object WaferAdditionReservedConv extends ProtoConverter[WaferAdditionReserved, WaferAdditionReservedPO] {
    override def toProto(e: WaferAdditionReserved): WaferAdditionReservedPO = WaferAdditionReservedPO(transferId = IdConv.toProto(e.transferId), waferIds = e.waferIds.map(IdConv.toProto).toSeq)
    override def fromProto(p: WaferAdditionReservedPO): WaferAdditionReserved = WaferAdditionReserved(transferId = IdConv.fromProto(p.transferId), waferIds = p.waferIds.map(IdConv.fromProto).toSet)
  }

  object WaferAdditionCommittedConv extends ProtoConverter[WaferAdditionCommitted, WaferAdditionCommittedPO] {
    override def toProto(e: WaferAdditionCommitted): WaferAdditionCommittedPO = WaferAdditionCommittedPO(transferId = IdConv.toProto(e.transferId))
    override def fromProto(p: WaferAdditionCommittedPO): WaferAdditionCommitted = WaferAdditionCommitted(transferId = IdConv.fromProto(p.transferId))
  }

  object WaferAdditionCanceledConv extends ProtoConverter[WaferAdditionCanceled, WaferAdditionCanceledPO] {
    override def toProto(e: WaferAdditionCanceled): WaferAdditionCanceledPO = WaferAdditionCanceledPO(transferId = IdConv.toProto(e.transferId))
    override def fromProto(p: WaferAdditionCanceledPO): WaferAdditionCanceled = WaferAdditionCanceled(transferId = IdConv.fromProto(p.transferId))
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

  // --- Process execution event converters ---
  object FoupLoadedConv extends ProtoConverter[FoupLoaded, FoupLoadedPO] {
    override def toProto(e: FoupLoaded): FoupLoadedPO = FoupLoadedPO(foupId = e.foupId, stockerId = e.stockerId)
    override def fromProto(p: FoupLoadedPO): FoupLoaded = FoupLoaded(foupId = p.foupId, stockerId = p.stockerId)
  }

  object TransportStartedConv extends ProtoConverter[TransportStarted, TransportStartedPO] {
    override def toProto(e: TransportStarted): TransportStartedPO = TransportStartedPO(foupId = e.foupId, fromArea = e.fromArea, toArea = e.toArea, estimatedMs = e.estimatedMs)
    override def fromProto(p: TransportStartedPO): TransportStarted = TransportStarted(foupId = p.foupId, fromArea = p.fromArea, toArea = p.toArea, estimatedMs = p.estimatedMs)
  }

  object TransportCompletedConv extends ProtoConverter[TransportCompleted, TransportCompletedPO] {
    override def toProto(e: TransportCompleted): TransportCompletedPO = TransportCompletedPO(foupId = e.foupId, equipmentId = e.equipmentId)
    override def fromProto(p: TransportCompletedPO): TransportCompleted = TransportCompleted(foupId = p.foupId, equipmentId = p.equipmentId)
  }

  object EquipmentJobStartedConv extends ProtoConverter[EquipmentJobStarted, EquipmentJobStartedPO] {
    override def toProto(e: EquipmentJobStarted): EquipmentJobStartedPO = EquipmentJobStartedPO(equipmentId = e.equipmentId, recipeId = e.recipeId)
    override def fromProto(p: EquipmentJobStartedPO): EquipmentJobStarted = EquipmentJobStarted(equipmentId = p.equipmentId, recipeId = p.recipeId)
  }

  object EquipmentJobCompletedConv extends ProtoConverter[EquipmentJobCompleted, EquipmentJobCompletedPO] {
    override def toProto(e: EquipmentJobCompleted): EquipmentJobCompletedPO = EquipmentJobCompletedPO(equipmentId = e.equipmentId, jobId = e.jobId, success = e.success)
    override def fromProto(p: EquipmentJobCompletedPO): EquipmentJobCompleted = EquipmentJobCompleted(equipmentId = p.equipmentId, jobId = p.jobId, success = p.success)
  }

  object WaferMeasuredConv extends ProtoConverter[WaferMeasured, WaferMeasuredPO] {
    override def toProto(e: WaferMeasured): WaferMeasuredPO = WaferMeasuredPO(waferId = e.waferId, cdNm = e.cdNm)
    override def fromProto(p: WaferMeasuredPO): WaferMeasured = WaferMeasured(waferId = p.waferId, cdNm = p.cdNm)
  }

  object WaferClassifiedConv extends ProtoConverter[WaferClassified, WaferClassifiedPO] {
    override def toProto(e: WaferClassified): WaferClassifiedPO = WaferClassifiedPO(waferId = e.waferId, classification = e.classification, reworkCount = e.reworkCount, cdValue = e.cdValue)
    override def fromProto(p: WaferClassifiedPO): WaferClassified = WaferClassified(waferId = p.waferId, classification = p.classification, reworkCount = p.reworkCount, cdValue = p.cdValue)
  }

  object WafersSplitForReworkConv extends ProtoConverter[WafersSplitForRework, WafersSplitForReworkPO] {
    override def toProto(e: WafersSplitForRework): WafersSplitForReworkPO = WafersSplitForReworkPO(reworkWaferIds = e.reworkWaferIds.toSeq, scrapWaferIds = e.scrapWaferIds.toSeq, iteration = e.iteration)
    override def fromProto(p: WafersSplitForReworkPO): WafersSplitForRework = WafersSplitForRework(reworkWaferIds = p.reworkWaferIds.toSet, scrapWaferIds = p.scrapWaferIds.toSet, iteration = p.iteration)
  }

  object WafersReworkedConv extends ProtoConverter[WafersReworked, WafersReworkedPO] {
    override def toProto(e: WafersReworked): WafersReworkedPO = WafersReworkedPO(waferIds = e.waferIds.toSeq)
    override def fromProto(p: WafersReworkedPO): WafersReworked = WafersReworked(waferIds = p.waferIds.toSet)
  }

  object WafersSentAsPilotConv extends ProtoConverter[WafersSentAsPilot, WafersSentAsPilotPO] {
    override def toProto(e: WafersSentAsPilot): WafersSentAsPilotPO = WafersSentAsPilotPO(waferIds = e.waferIds.toSeq)
    override def fromProto(p: WafersSentAsPilotPO): WafersSentAsPilot = WafersSentAsPilot(waferIds = p.waferIds.toSet)
  }

  object WafersSampledConv extends ProtoConverter[WafersSampled, WafersSampledPO] {
    override def toProto(e: WafersSampled): WafersSampledPO = WafersSampledPO(sampleIds = e.sampleIds.toSeq, skipIds = e.skipIds.toSeq)
    override def fromProto(p: WafersSampledPO): WafersSampled = WafersSampled(sampleIds = p.sampleIds.toSet, skipIds = p.skipIds.toSet)
  }

  object WafersHeldConv extends ProtoConverter[WafersHeld, WafersHeldPO] {
    override def toProto(e: WafersHeld): WafersHeldPO = WafersHeldPO(waferIds = e.waferIds.toSeq, reason = e.reason)
    override def fromProto(p: WafersHeldPO): WafersHeld = WafersHeld(waferIds = p.waferIds.toSet, reason = p.reason)
  }

  object WafersReleasedConv extends ProtoConverter[WafersReleased, WafersReleasedPO] {
    override def toProto(e: WafersReleased): WafersReleasedPO = WafersReleasedPO(waferIds = e.waferIds.toSeq)
    override def fromProto(p: WafersReleasedPO): WafersReleased = WafersReleased(waferIds = p.waferIds.toSet)
  }

  object ProcessCompletedConv extends ProtoConverter[ProcessCompleted, ProcessCompletedPO] {
    override def toProto(e: ProcessCompleted): ProcessCompletedPO = ProcessCompletedPO(lotId = e.lotId, passCount = e.passCount, scrapCount = e.scrapCount, reworkCount = e.reworkCount)
    override def fromProto(p: ProcessCompletedPO): ProcessCompleted = ProcessCompleted(lotId = p.lotId, passCount = p.passCount, scrapCount = p.scrapCount, reworkCount = p.reworkCount)
  }

  // --- State Snapshot ---
  object LotStateConv extends ProtoConverter[LotState, LotStatePO] {
    override def toProto(s: LotState): LotStatePO = LotStatePO(
      lotId = IdConv.toProto(s.lotId),
      productId = s.productId,
      waferIds = s.waferIds.map(IdConv.toProto).toSeq,
      reservedWafers = toProtoMap(s.reservedWafers, IdConv, WaferIdSetConv),
      incomingWafers = toProtoMap(s.incomingWafers, IdConv, WaferIdSetConv),
      phase = s.phase.toString,
      completedTransferIds = s.completedTransferIds.map(IdConv.toProto).toSeq,
      currentStepIndex = s.currentStepIndex,
      areaVisitHistory = s.areaVisitHistory,
      routingStepReentry = s.routingStepReentry.map { case (k, v) => IntEntry(key = k, value = v) }.toSeq,
      loadedFoupId = s.loadedFoupId.getOrElse(""),
      completedJobs = s.completedJobs.toSeq,
      measuredWafers = s.measuredWafers.toSeq,
      waferClassifications = s.waferClassifications.map { case (wid, r) =>
        WaferClassResultPO(waferId = wid, classification = r.classification, cdValueNm = r.cdValueNm, reworkCount = r.reworkCount)
      }.toSeq
    )

    override def fromProto(p: LotStatePO): LotState = LotState(
      lotId = IdConv.fromProto(p.lotId),
      productId = p.productId,
      waferIds = p.waferIds.map(IdConv.fromProto).toSet,
      reservedWafers = fromProtoMap(p.reservedWafers, IdConv, WaferIdSetConv),
      incomingWafers = fromProtoMap(p.incomingWafers, IdConv, WaferIdSetConv),
      phase = parsePhase(p.phase),
      completedTransferIds = p.completedTransferIds.map(IdConv.fromProto).toSet,
      currentStepIndex = p.currentStepIndex,
      areaVisitHistory = p.areaVisitHistory.toList,
      routingStepReentry = p.routingStepReentry.map(e => e.key -> e.value).toMap,
      loadedFoupId = if (p.loadedFoupId.isEmpty) None else Some(p.loadedFoupId),
      completedJobs = p.completedJobs.toSet,
      measuredWafers = p.measuredWafers.toSet,
      waferClassifications = p.waferClassifications.map { r => r.waferId -> WaferClassResultConv.fromProto(r) }.toMap
    )

    private def parsePhase(s: String): LotPhase = s match {
      case "Empty" => Empty; case "Active" => Active; case "Sealed" => Sealed; case "Completed" => Completed; case _ => Empty
    }
  }
}
