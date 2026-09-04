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

  // --- SplitReason helpers ---
  private def splitReasonToString(sr: Option[SplitReason]): String = sr match {
    case Some(ReworkSplit) => "rework"
    case Some(ScrapSplit) => "scrap"
    case Some(PilotSplit) => "pilot"
    case Some(SampleSplit) => "sample"
    case Some(HoldSplit) => "hold"
    case None => ""
  }
  private def stringToSplitReason(s: String): Option[SplitReason] = s match {
    case "rework" => Some(ReworkSplit)
    case "scrap" => Some(ScrapSplit)
    case "pilot" => Some(PilotSplit)
    case "sample" => Some(SampleSplit)
    case "hold" => Some(HoldSplit)
    case _ => None
  }

  // --- Existing Events ---
  object LotCreatedConv extends ProtoConverter[LotCreated, LotCreatedPO] {
    override def toProto(e: LotCreated): LotCreatedPO = LotCreatedPO(
      productId = e.productId,
      waferIds = e.waferNames.keys.map(IdConv.toProto).toSeq,
      waferNames = e.waferNames.map { case (id, name) => WaferNameEntryPO(waferId = IdConv.toProto(id), name = name) }.toSeq,
      parentLotId = e.parentLotId.map(IdConv.toProto).getOrElse(""),
      splitReason = splitReasonToString(e.splitReason),
      workOrderId = e.workOrderId.getOrElse("")
    )
    override def fromProto(p: LotCreatedPO): LotCreated = {
      val waferNames: Map[Id, String] = if (p.waferNames.nonEmpty) {
        p.waferNames.map(e => IdConv.fromProto(e.waferId) -> e.name).toMap
      } else {
        // Backward compat: old journal without names
        p.waferIds.map(id => IdConv.fromProto(id) -> "").toMap
      }
      LotCreated(
        productId = p.productId,
        waferNames = waferNames,
        parentLotId = if (p.parentLotId.isEmpty) None else Some(IdConv.fromProto(p.parentLotId)),
        splitReason = stringToSplitReason(p.splitReason),
        workOrderId = if (p.workOrderId.isEmpty) None else Some(p.workOrderId)
      )
    }
  }

  object WaferRemovalReservedConv extends ProtoConverter[WaferRemovalReserved, WaferRemovalReservedPO] {
    override def toProto(e: WaferRemovalReserved): WaferRemovalReservedPO = WaferRemovalReservedPO(
      transferId = IdConv.toProto(e.transferId),
      waferIds = e.waferIds.map(IdConv.toProto).toSeq,
      waferNames = e.waferNames.toSeq
    )
    override def fromProto(p: WaferRemovalReservedPO): WaferRemovalReserved = WaferRemovalReserved(
      transferId = IdConv.fromProto(p.transferId),
      waferIds = p.waferIds.map(IdConv.fromProto).toSet,
      waferNames = p.waferNames.toSet
    )
  }

  object WaferRemovalCommittedConv extends ProtoConverter[WaferRemovalCommitted, WaferRemovalCommittedPO] {
    override def toProto(e: WaferRemovalCommitted): WaferRemovalCommittedPO = WaferRemovalCommittedPO(
      transferId = IdConv.toProto(e.transferId),
      waferNames = e.waferNames.toSeq
    )
    override def fromProto(p: WaferRemovalCommittedPO): WaferRemovalCommitted = WaferRemovalCommitted(
      transferId = IdConv.fromProto(p.transferId),
      waferNames = p.waferNames.toSet
    )
  }

  object WaferRemovalReleasedConv extends ProtoConverter[WaferRemovalReleased, WaferRemovalReleasedPO] {
    override def toProto(e: WaferRemovalReleased): WaferRemovalReleasedPO = WaferRemovalReleasedPO(transferId = IdConv.toProto(e.transferId))
    override def fromProto(p: WaferRemovalReleasedPO): WaferRemovalReleased = WaferRemovalReleased(transferId = IdConv.fromProto(p.transferId))
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

  private def parseWaferId(s: String): Id =
    try { java.util.UUID.fromString(s) } catch { case _: IllegalArgumentException => java.util.UUID.nameUUIDFromBytes(s.getBytes) }

  object WaferMeasuredConv extends ProtoConverter[WaferMeasured, WaferMeasuredPO] {
    override def toProto(e: WaferMeasured): WaferMeasuredPO = WaferMeasuredPO(waferId = IdConv.toProto(e.waferId), cdNm = e.cdNm)
    override def fromProto(p: WaferMeasuredPO): WaferMeasured = WaferMeasured(waferId = parseWaferId(p.waferId), cdNm = p.cdNm)
  }

  object WaferClassifiedConv extends ProtoConverter[WaferClassified, WaferClassifiedPO] {
    override def toProto(e: WaferClassified): WaferClassifiedPO = WaferClassifiedPO(waferId = IdConv.toProto(e.waferId), classification = e.classification, reworkCount = e.reworkCount, cdValue = e.cdValue)
    override def fromProto(p: WaferClassifiedPO): WaferClassified = WaferClassified(waferId = parseWaferId(p.waferId), classification = p.classification, reworkCount = p.reworkCount, cdValue = p.cdValue)
  }

  object WafersSplitForReworkConv extends ProtoConverter[WafersSplitForRework, WafersSplitForReworkPO] {
    override def toProto(e: WafersSplitForRework): WafersSplitForReworkPO = WafersSplitForReworkPO(reworkWaferIds = e.reworkWaferIds.toSeq, scrapWaferIds = e.scrapWaferIds.toSeq, iteration = e.iteration)
    override def fromProto(p: WafersSplitForReworkPO): WafersSplitForRework = WafersSplitForRework(reworkWaferIds = p.reworkWaferIds.toSet, scrapWaferIds = p.scrapWaferIds.toSet, iteration = p.iteration)
  }

  object SubLotCreatedConv extends ProtoConverter[SubLotCreated, SubLotCreatedPO] {
    override def toProto(e: SubLotCreated): SubLotCreatedPO = SubLotCreatedPO(
      childLotId = IdConv.toProto(e.childLotId),
      splitReason = splitReasonToString(Some(e.splitReason)),
      waferIds = e.waferIds.map(IdConv.toProto).toSeq
    )
    override def fromProto(p: SubLotCreatedPO): SubLotCreated = SubLotCreated(
      childLotId = IdConv.fromProto(p.childLotId),
      splitReason = stringToSplitReason(p.splitReason).getOrElse(ReworkSplit),
      waferIds = p.waferIds.map(IdConv.fromProto).toSet
    )
  }

  object SubLotMergedConv extends ProtoConverter[SubLotMerged, SubLotMergedPO] {
    override def toProto(e: SubLotMerged): SubLotMergedPO = SubLotMergedPO(
      childLotId = IdConv.toProto(e.childLotId),
      waferIds = e.waferIds.map(IdConv.toProto).toSeq
    )
    override def fromProto(p: SubLotMergedPO): SubLotMerged = SubLotMerged(
      childLotId = IdConv.fromProto(p.childLotId),
      waferIds = p.waferIds.map(IdConv.fromProto).toSet
    )
  }

  object SubLotScrappedConv extends ProtoConverter[SubLotScrapped, SubLotScrappedPO] {
    override def toProto(e: SubLotScrapped): SubLotScrappedPO = SubLotScrappedPO(
      childLotId = IdConv.toProto(e.childLotId),
      reason = e.reason,
      waferIds = e.waferIds.map(IdConv.toProto).toSeq
    )
    override def fromProto(p: SubLotScrappedPO): SubLotScrapped = SubLotScrapped(
      childLotId = IdConv.fromProto(p.childLotId),
      reason = p.reason,
      waferIds = p.waferIds.map(IdConv.fromProto).toSet
    )
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

  object LotFailedConv extends ProtoConverter[LotFailed, LotFailedPO] {
    override def toProto(e: LotFailed): LotFailedPO = LotFailedPO(reason = e.reason, failedAt = e.failedAt)
    override def fromProto(p: LotFailedPO): LotFailed = LotFailed(reason = p.reason, failedAt = p.failedAt)
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
      loadedFoupId = s.loadedFoupId.getOrElse(""),
      measuredWafers = s.measuredWafers.map(IdConv.toProto).toSeq,
      waferClassifications = s.waferClassifications.map { case (id, r) =>
        WaferClassResultPO(waferId = IdConv.toProto(id), classification = r.classification, cdValueNm = r.cdValueNm, reworkCount = r.reworkCount)
      }.toSeq,
      parentLotId = s.parentLotId.map(IdConv.toProto).getOrElse(""),
      splitReason = splitReasonToString(s.splitReason),
      workOrderId = s.workOrderId.getOrElse("")
    )

    override def fromProto(p: LotStatePO): LotState = {
      // Reconstruct unified wafers map from old-style snapshot fields
      val classMap = p.waferClassifications.map(r => parseWaferId(r.waferId) -> WaferClassResultConv.fromProto(r)).toMap
      val measuredSet = p.measuredWafers.map(parseWaferId).toSet
      val waferIds = p.waferIds.map(IdConv.fromProto)
      val waferEntries: Map[Id, WaferState] = if (waferIds.nonEmpty) {
        waferIds.zipWithIndex.map { case (id, idx) =>
          val cls = classMap.get(id)
          id -> WaferState(
            name = s"WAFER-${idx + 1}",
            classification = cls.map(_.classification),
            reworkCount = cls.map(_.reworkCount).getOrElse(0),
            cdValue = cls.map(_.cdValueNm),
            measured = measuredSet.contains(id)
          )
        }.toMap
      } else Map.empty
      LotState(
        lotId = IdConv.fromProto(p.lotId),
        productId = p.productId,
        wafers = waferEntries,
        reservedWafers = fromProtoMap(p.reservedWafers, IdConv, WaferIdSetConv),
        incomingWafers = fromProtoMap(p.incomingWafers, IdConv, WaferIdSetConv),
        phase = parsePhase(p.phase),
        completedTransferIds = p.completedTransferIds.map(IdConv.fromProto).toSet,
        loadedFoupId = if (p.loadedFoupId.isEmpty) None else Some(p.loadedFoupId),
        parentLotId = if (p.parentLotId.isEmpty) None else Some(IdConv.fromProto(p.parentLotId)),
        splitReason = stringToSplitReason(p.splitReason),
        workOrderId = if (p.workOrderId.isEmpty) None else Some(p.workOrderId)
      )
    }

    private def parsePhase(s: String): LotPhase = s match {
      case "Empty" => Empty; case "Active" => Active; case "Sealed" => Sealed; case "Completed" => Completed; case "AwaitingSubLot" => AwaitingSubLot; case _ => Empty
    }
  }
}
