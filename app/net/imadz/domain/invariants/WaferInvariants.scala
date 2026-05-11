package net.imadz.domain.invariants

import net.imadz.common.CommonTypes.{Id, InvariantRule, iMadzError}
import net.imadz.domain.entities.WaferEntity._

object WaferInvariants {

  implicit object CreateWaferRule extends InvariantRule[WaferEvent, WaferState, Id] {
    def apply(state: WaferState, param: Id): Either[iMadzError, List[WaferEvent]] = {
      val lotId = param
      if (state.lotId.isDefined)
        Left(iMadzError("WFR_001", s"Wafer ${state.waferId} already assigned to lot ${state.lotId.get}"))
      else
        Right(List(WaferCreated(lotId)))
    }
  }

  implicit object ReserveTransferRule extends InvariantRule[WaferEvent, WaferState, (Id, Id)] {
    def apply(state: WaferState, param: (Id, Id)): Either[iMadzError, List[WaferEvent]] = {
      val (transferId, targetLotId) = param

      // Idempotency: same transfer already reserved
      if (state.reservedTransfer.exists(_._1 == transferId))
        return Right(Nil)

      // Cannot transfer scrapped wafers
      if (state.status == Scrapped)
        return Left(iMadzError("WFR_010", s"Wafer ${state.waferId} is scrapped, cannot transfer"))

      // Wafer must already be assigned to a lot
      if (state.lotId.isEmpty)
        return Left(iMadzError("WFR_011", s"Wafer ${state.waferId} is not assigned to any lot"))

      // Cannot reserve a new transfer if already reserved by another transfer
      if (state.reservedTransfer.isDefined && !state.reservedTransfer.exists(_._1 == transferId))
        return Left(iMadzError("WFR_012", s"Wafer ${state.waferId} already reserved for another transfer"))

      Right(List(WaferTransferReserved(transferId, targetLotId)))
    }
  }

  implicit object CommitTransferRule extends InvariantRule[WaferEvent, WaferState, (Id, Id)] {
    def apply(state: WaferState, param: (Id, Id)): Either[iMadzError, List[WaferEvent]] = {
      val (transferId, targetLotId) = param
      if (state.reservedTransfer.exists(_._1 == transferId))
        Right(List(WaferTransferCommitted(transferId, targetLotId)))
      else
        Left(iMadzError("WFR_013", s"Transfer $transferId not found in reserved transfer for wafer ${state.waferId}"))
    }
  }

  implicit object ReleaseTransferRule extends InvariantRule[WaferEvent, WaferState, Id] {
    def apply(state: WaferState, param: Id): Either[iMadzError, List[WaferEvent]] = {
      val transferId = param
      if (state.reservedTransfer.exists(_._1 == transferId))
        Right(List(WaferTransferReleased(transferId)))
      else
        Left(iMadzError("WFR_014", s"Transfer $transferId not found in reserved transfer for wafer ${state.waferId}"))
    }
  }

  implicit object ScrapWaferRule extends InvariantRule[WaferEvent, WaferState, String] {
    def apply(state: WaferState, param: String): Either[iMadzError, List[WaferEvent]] = {
      if (state.status == Scrapped)
        Left(iMadzError("WFR_020", s"Wafer ${state.waferId} is already scrapped"))
      else
        Right(List(WaferScrapped(param)))
    }
  }

  implicit object ChangeStatusRule extends InvariantRule[WaferEvent, WaferState, WaferStatus] {
    def apply(state: WaferState, param: WaferStatus): Either[iMadzError, List[WaferEvent]] = {
      Right(List(WaferStatusChanged(param)))
    }
  }
}
