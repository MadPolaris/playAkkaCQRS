package net.imadz.domain.entities.behaviors

import net.imadz.domain.entities.WaferEntity._

object WaferEventHandler {
  def apply: WaferEventHandler = (state, event) => event match {
    case WaferCreated(lotId) =>
      state.copy(lotId = Some(lotId), status = Active)

    case WaferAssigned(lotId) =>
      state.copy(lotId = Some(lotId))

    case WaferTransferReserved(transferId, targetLotId) =>
      state.copy(reservedTransfer = Some((transferId, targetLotId)))

    case WaferTransferCommitted(transferId, targetLotId) =>
      state.copy(
        lotId = Some(targetLotId),
        reservedTransfer = None
      )

    case WaferTransferReleased(transferId) =>
      state.copy(reservedTransfer = None)

    case WaferScrapped(reason) =>
      state.copy(status = Scrapped)

    case WaferStatusChanged(newStatus) =>
      state.copy(status = newStatus)
  }
}
