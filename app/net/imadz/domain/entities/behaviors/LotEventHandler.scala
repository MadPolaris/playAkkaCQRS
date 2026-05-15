package net.imadz.domain.entities.behaviors

import net.imadz.domain.entities.LotEntity._

object LotEventHandler {
  def apply: LotEventHandler = (state, event) => event match {
    case LotCreated(productId, waferIds) =>
      state.copy(productId = productId, waferIds = waferIds, phase = Active)

    case WaferRemovalReserved(transferId, waferIds) =>
      state.copy(reservedWafers = state.reservedWafers + (transferId -> waferIds))

    case WaferRemovalCommitted(transferId) =>
      val removedWafers = state.reservedWafers.getOrElse(transferId, Set.empty)
      state.copy(
        waferIds = state.waferIds -- removedWafers,
        reservedWafers = state.reservedWafers - transferId,
        completedTransferIds = state.completedTransferIds + transferId
      )

    case WaferRemovalReleased(transferId) =>
      state.copy(reservedWafers = state.reservedWafers - transferId)

    case WaferAdditionReserved(transferId, waferIds) =>
      state.copy(incomingWafers = state.incomingWafers + (transferId -> waferIds))

    case WaferAdditionCommitted(transferId) =>
      val addedWafers = state.incomingWafers.getOrElse(transferId, Set.empty)
      state.copy(
        waferIds = state.waferIds ++ addedWafers,
        incomingWafers = state.incomingWafers - transferId,
        completedTransferIds = state.completedTransferIds + transferId
      )

    case WaferAdditionCanceled(transferId) =>
      state.copy(incomingWafers = state.incomingWafers - transferId)

    case PhaseStarted(_) => state
    case PhaseCompleted(_) => state

    case LotSealed() =>
      state.copy(phase = Sealed)

    // Process execution events (equipment reports)
    case FoupLoaded(foupId, _) =>
      if (state.loadedFoupId.isDefined) state
      else state.copy(loadedFoupId = Some(foupId))

    case TransportStarted(_, _, _, _) => state
    case TransportCompleted(_, _) => state
    case EquipmentJobStarted(_, _) => state

    case EquipmentJobCompleted(_, jobId, _) =>
      if (state.completedJobs.contains(jobId)) state
      else state.copy(completedJobs = state.completedJobs + jobId)

    case WaferMeasured(waferId, _) =>
      if (state.measuredWafers.contains(waferId)) state
      else state.copy(measuredWafers = state.measuredWafers + waferId)

    case WaferClassified(waferId, classification, reworkCount, cdValue) =>
      if (state.waferClassifications.contains(waferId)) state
      else state.copy(
        waferClassifications = state.waferClassifications + (waferId ->
          WaferClassResult(classification, cdValue, reworkCount))
      )

    case WafersSplitForRework(_, _, _) => state
    case WafersReworked(_) => state
    case WafersSentAsPilot(_) => state
    case WafersSampled(_, _) => state
    case WafersHeld(_, _) => state
    case WafersReleased(_) => state

    case ProcessCompleted(_, _, _, _) => state
  }
}
