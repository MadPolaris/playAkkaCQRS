package net.imadz.infrastructure.persistence.converters

import net.imadz.common.serialization.PrimitiveConverter
import net.imadz.domain.entities.FabProcessEntity._
import net.imadz.infrastructure.proto.process._

trait ProcessProtoConverters extends PrimitiveConverter {

  // --- Events ---

  object ProcessStartedConv extends ProtoConverter[ProcessStarted, ProcessStartedPO] {
    override def toProto(e: ProcessStarted): ProcessStartedPO =
      ProcessStartedPO(lotId = e.lotId, waferIds = e.waferIds.toSeq, lotSize = e.lotSize)
    override def fromProto(p: ProcessStartedPO): ProcessStarted =
      ProcessStarted(lotId = p.lotId, waferIds = p.waferIds.toSet, lotSize = p.lotSize)
  }

  object FoupLoadedConv extends ProtoConverter[FoupLoaded, FoupLoadedPO] {
    override def toProto(e: FoupLoaded): FoupLoadedPO =
      FoupLoadedPO(foupId = e.foupId, stockerId = e.stockerId)
    override def fromProto(p: FoupLoadedPO): FoupLoaded =
      FoupLoaded(foupId = p.foupId, stockerId = p.stockerId)
  }

  object TransportStartedConv extends ProtoConverter[TransportStarted, TransportStartedPO] {
    override def toProto(e: TransportStarted): TransportStartedPO =
      TransportStartedPO(foupId = e.foupId, fromArea = e.fromArea, toArea = e.toArea, estimatedMs = e.estimatedMs)
    override def fromProto(p: TransportStartedPO): TransportStarted =
      TransportStarted(foupId = p.foupId, fromArea = p.fromArea, toArea = p.toArea, estimatedMs = p.estimatedMs)
  }

  object TransportCompletedConv extends ProtoConverter[TransportCompleted, TransportCompletedPO] {
    override def toProto(e: TransportCompleted): TransportCompletedPO =
      TransportCompletedPO(foupId = e.foupId, equipmentId = e.equipmentId)
    override def fromProto(p: TransportCompletedPO): TransportCompleted =
      TransportCompleted(foupId = p.foupId, equipmentId = p.equipmentId)
  }

  object EquipmentJobStartedConv extends ProtoConverter[EquipmentJobStarted, EquipmentJobStartedPO] {
    override def toProto(e: EquipmentJobStarted): EquipmentJobStartedPO =
      EquipmentJobStartedPO(equipmentId = e.equipmentId, recipeId = e.recipeId)
    override def fromProto(p: EquipmentJobStartedPO): EquipmentJobStarted =
      EquipmentJobStarted(equipmentId = p.equipmentId, recipeId = p.recipeId)
  }

  object EquipmentJobCompletedConv extends ProtoConverter[EquipmentJobCompleted, EquipmentJobCompletedPO] {
    override def toProto(e: EquipmentJobCompleted): EquipmentJobCompletedPO =
      EquipmentJobCompletedPO(equipmentId = e.equipmentId, jobId = e.jobId, success = e.success)
    override def fromProto(p: EquipmentJobCompletedPO): EquipmentJobCompleted =
      EquipmentJobCompleted(equipmentId = p.equipmentId, jobId = p.jobId, success = p.success)
  }

  object WaferMeasuredConv extends ProtoConverter[WaferMeasured, WaferMeasuredPO] {
    override def toProto(e: WaferMeasured): WaferMeasuredPO =
      WaferMeasuredPO(waferId = e.waferId, cdNm = e.cdNm)
    override def fromProto(p: WaferMeasuredPO): WaferMeasured =
      WaferMeasured(waferId = p.waferId, cdNm = p.cdNm)
  }

  object WaferClassifiedConv extends ProtoConverter[WaferClassified, WaferClassifiedPO] {
    override def toProto(e: WaferClassified): WaferClassifiedPO =
      WaferClassifiedPO(waferId = e.waferId, classification = e.classification, reworkCount = e.reworkCount, cdValue = e.cdValue)
    override def fromProto(p: WaferClassifiedPO): WaferClassified =
      WaferClassified(waferId = p.waferId, classification = p.classification, reworkCount = p.reworkCount, cdValue = p.cdValue)
  }

  object WafersSplitForReworkConv extends ProtoConverter[WafersSplitForRework, WafersSplitForReworkPO] {
    override def toProto(e: WafersSplitForRework): WafersSplitForReworkPO =
      WafersSplitForReworkPO(reworkWaferIds = e.reworkWaferIds.toSeq, scrapWaferIds = e.scrapWaferIds.toSeq, iteration = e.iteration)
    override def fromProto(p: WafersSplitForReworkPO): WafersSplitForRework =
      WafersSplitForRework(reworkWaferIds = p.reworkWaferIds.toSet, scrapWaferIds = p.scrapWaferIds.toSet, iteration = p.iteration)
  }

  object WafersReworkedConv extends ProtoConverter[WafersReworked, WafersReworkedPO] {
    override def toProto(e: WafersReworked): WafersReworkedPO =
      WafersReworkedPO(waferIds = e.waferIds.toSeq)
    override def fromProto(p: WafersReworkedPO): WafersReworked =
      WafersReworked(waferIds = p.waferIds.toSet)
  }

  object ProcessCompletedConv extends ProtoConverter[ProcessCompleted, ProcessCompletedPO] {
    override def toProto(e: ProcessCompleted): ProcessCompletedPO =
      ProcessCompletedPO(lotId = e.lotId, passCount = e.passCount, scrapCount = e.scrapCount, reworkCount = e.reworkCount)
    override def fromProto(p: ProcessCompletedPO): ProcessCompleted =
      ProcessCompleted(lotId = p.lotId, passCount = p.passCount, scrapCount = p.scrapCount, reworkCount = p.reworkCount)
  }

  // --- State Snapshot ---

  object WaferClassResultConv extends ProtoConverter[WaferClassResult, WaferClassResultPO] {
    override def toProto(r: WaferClassResult): WaferClassResultPO =
      WaferClassResultPO(waferId = "", classification = r.classification, cdValueNm = r.cdValueNm, reworkCount = r.reworkCount)
    override def fromProto(p: WaferClassResultPO): WaferClassResult =
      WaferClassResult(classification = p.classification, cdValueNm = p.cdValueNm, reworkCount = p.reworkCount)
  }
}
