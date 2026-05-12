package net.imadz.domain.entities.behaviors

import net.imadz.domain.entities.FabProcessEntity._

object FabProcessEventHandler {
  def apply: ProcessEventHandler = (state, event) => event match {
    case ProcessStarted(lotId, waferIds, lotSize) =>
      state.copy(lotId = lotId, waferIds = waferIds, lotSize = lotSize, phase = ProcessActive)

    case FoupLoaded(_, _) => state

    case TransportStarted(_, _, _, _) => state

    case TransportCompleted(_, _) => state

    case EquipmentJobStarted(_, _) => state

    case EquipmentJobCompleted(_, _, _) => state

    case WaferMeasured(_, _) => state

    case WaferClassified(waferId, classification, reworkCount, cdValue) =>
      val entry = WaferClassResult(classification, cdValue, reworkCount)
      val passInc = if (classification == "PASS") 1 else 0
      val scrapInc = if (classification == "SCRAP") 1 else 0
      val rwkInc = if (reworkCount > 0) 1 else 0
      state.copy(
        waferClassifications = state.waferClassifications + (waferId -> entry),
        passCount = state.passCount + passInc,
        scrapCount = state.scrapCount + scrapInc,
        reworkCount = state.reworkCount + rwkInc
      )

    case WafersSplitForRework(_, _, _) => state

    case WafersReworked(_) => state

    case ProcessCompleted(_, passCount, scrapCount, reworkCount) =>
      state.copy(passCount = passCount, scrapCount = scrapCount, reworkCount = reworkCount, phase = ProcessCompleted)
  }
}
