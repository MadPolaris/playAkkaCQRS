package net.imadz.domain.entities.behaviors

import net.imadz.domain.entities.LotEntity._

object LotEventHandler {
  def apply: LotEventHandler = (state, event) => event match {
    case LotCreated(productId, waferNames, parentLotId, splitReason) =>
      state.copy(
        productId = productId,
        wafers = waferNames.map { case (id, name) => id -> WaferState(name = name) },
        phase = Active,
        parentLotId = parentLotId,
        splitReason = splitReason
      )

    case WaferRemovalReserved(transferId, waferIds, waferNames) =>
      state.copy(
        reservedWafers = state.reservedWafers + (transferId -> waferIds),
        reservedWaferNames = state.reservedWaferNames + (transferId -> waferNames)
      )

    case WaferRemovalCommitted(transferId, _) =>
      val removedWafers = state.reservedWafers.getOrElse(transferId, Set.empty)
      val newWafers = state.wafers -- removedWafers
      state.copy(
        wafers = newWafers,
        reservedWafers = state.reservedWafers - transferId,
        reservedWaferNames = state.reservedWaferNames - transferId,
        completedTransferIds = state.completedTransferIds + transferId,
        phase = if (newWafers.isEmpty && state.parentLotId.isDefined) Sealed else state.phase
      )

    case WaferRemovalReleased(transferId) =>
      state.copy(
        reservedWafers = state.reservedWafers - transferId,
        reservedWaferNames = state.reservedWaferNames - transferId
      )

    case WaferAdditionReserved(transferId, waferIds) =>
      state.copy(incomingWafers = state.incomingWafers + (transferId -> waferIds))

    case WaferAdditionCommitted(transferId) =>
      val addedWafers = state.incomingWafers.getOrElse(transferId, Set.empty)
      state.copy(
        wafers = state.wafers ++ addedWafers.map(id => id -> WaferState(name = id.toString.take(8))),
        incomingWafers = state.incomingWafers - transferId,
        completedTransferIds = state.completedTransferIds + transferId
      )

    case WaferAdditionCanceled(transferId) =>
      state.copy(incomingWafers = state.incomingWafers - transferId)

    case PhaseStarted(_) => state
    case PhaseCompleted(_) => state

    case LotSealed() =>
      state.copy(phase = Sealed)

    case FoupLoaded(foupId, _) =>
      if (state.loadedFoupId.isDefined) state
      else state.copy(loadedFoupId = Some(foupId))

    case TransportStarted(_, _, _, _) => state
    case TransportCompleted(_, _) => state
    case EquipmentJobStarted(_, _) => state
    case EquipmentJobCompleted(_, _, _) => state

    case WaferMeasured(waferId, cdNm) =>
      state.wafers.get(waferId) match {
        case Some(ws) => state.copy(wafers = state.wafers + (waferId -> ws.copy(measured = true, cdValue = Some(cdNm))))
        case None => state
      }

    case WaferClassified(waferId, classification, reworkCount, cdValue) =>
      state.wafers.get(waferId) match {
        case Some(ws) => state.copy(wafers = state.wafers + (waferId -> ws.copy(
          classification = Some(classification),
          reworkCount = reworkCount,
          cdValue = Some(cdValue),
          measured = true,
          status = if (classification == "SCRAP") WaferScrapped else ws.status
        )))
        case None => state
      }

    case WafersSplitForRework(_, _, _) => state
    case WafersReworked(_) => state
    case WafersSentAsPilot(_) => state
    case WafersSampled(_, _) => state
    case WafersHeld(_, _) => state
    case WafersReleased(_) => state

    case ProcessCompleted(_, _, _, _) => state
  }
}
