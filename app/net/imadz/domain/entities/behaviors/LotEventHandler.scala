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

    case PhaseStarted(phaseId) =>
      state

    case PhaseCompleted(phaseId) =>
      state

    case LotSealed() =>
      state.copy(phase = Sealed)
  }
}
