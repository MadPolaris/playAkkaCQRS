package net.imadz.domain.invariants

import net.imadz.common.CommonTypes.{Id, InvariantRule, iMadzError}
import net.imadz.domain.entities.LotEntity._

object LotInvariants {

  implicit object CreateLotRule extends InvariantRule[LotEvent, LotState, (String, Map[Id, String], Option[Id], Option[SplitReason])] {
    def apply(state: LotState, param: (String, Map[Id, String], Option[Id], Option[SplitReason])): Either[iMadzError, List[LotEvent]] = {
      val (productId, waferNames, parentLotId, splitReason) = param
      if (state.phase != Empty)
        Left(iMadzError("LOT_001", s"Lot ${state.lotId} already created, cannot create again"))
      else if (productId.isEmpty)
        Left(iMadzError("LOT_002", "Product ID must not be empty"))
      else if (waferNames.size > 25)
        Left(iMadzError("LOT_004", s"Lot cannot exceed FOUP capacity of 25 wafers, got ${waferNames.size}"))
      else
        Right(List(LotCreated(productId, waferNames, parentLotId, splitReason)))
    }
  }

  implicit object ReserveWaferRemovalRule extends InvariantRule[LotEvent, LotState, (Id, Set[Id], Set[String])] {
    def apply(state: LotState, param: (Id, Set[Id], Set[String])): Either[iMadzError, List[LotEvent]] = {
      val (transferId, waferIds, waferNames) = param

      // Idempotency: if transferId already exists in reservedWafers
      if (state.reservedWafers.contains(transferId))
        return Right(Nil)

      // Validate lot is in Active phase
      if (state.phase != Active)
        return Left(iMadzError("LOT_010", s"Lot ${state.lotId} is not in Active phase (current: ${state.phase})"))

      // Validate all wafers belong to this lot
      val unknownWafers = waferIds.filterNot(state.waferIds.contains)
      if (unknownWafers.nonEmpty)
        return Left(iMadzError("LOT_011", s"Wafers $unknownWafers not found in lot ${state.lotId}"))

      // Validate wafers are not already reserved by another transfer
      val allReservedOut = state.reservedWafers.values.flatten.toSet
      val alreadyReserved = waferIds.intersect(allReservedOut)
      if (alreadyReserved.nonEmpty)
        return Left(iMadzError("LOT_012", s"Wafers $alreadyReserved already reserved for another transfer"))

      Right(List(WaferRemovalReserved(transferId, waferIds, waferNames)))
    }
  }

  implicit object CommitWaferRemovalRule extends InvariantRule[LotEvent, LotState, Id] {
    def apply(state: LotState, param: Id): Either[iMadzError, List[LotEvent]] = {
      val transferId = param
      if (state.reservedWafers.contains(transferId))
        Right(List(WaferRemovalCommitted(transferId, state.reservedWaferNames.getOrElse(transferId, Set.empty))))
      else if (state.completedTransferIds.contains(transferId))
        Right(Nil) // already committed — idempotent
      else
        Left(iMadzError("LOT_013", s"Transfer $transferId not found in reserved wafers"))
    }
  }

  implicit object ReleaseReservedWaferRule extends InvariantRule[LotEvent, LotState, Id] {
    def apply(state: LotState, param: Id): Either[iMadzError, List[LotEvent]] = {
      val transferId = param
      if (state.reservedWafers.contains(transferId))
        Right(List(WaferRemovalReleased(transferId)))
      else if (state.completedTransferIds.contains(transferId))
        Right(Nil) // already committed, release is a no-op — idempotent
      else
        Left(iMadzError("LOT_014", s"Transfer $transferId not found in reserved wafers"))
    }
  }

  implicit object ReserveAddWaferRule extends InvariantRule[LotEvent, LotState, (Id, Set[Id])] {
    def apply(state: LotState, param: (Id, Set[Id])): Either[iMadzError, List[LotEvent]] = {
      val (transferId, waferIds) = param

      // Idempotency
      if (state.incomingWafers.contains(transferId))
        return Right(Nil)

      // Validate lot can accept wafers (Active or Empty for split-to-new-lot)
      if (state.phase != Active && state.phase != Empty)
        return Left(iMadzError("LOT_020", s"Lot ${state.lotId} cannot accept wafers in phase ${state.phase}"))

      // FOUP capacity check: current + all incoming reservations + new wafers <= 25
      val currentOccupancy = state.waferIds.size + state.incomingWafers.values.flatten.toSet.size
      if (currentOccupancy + waferIds.size > 25)
        return Left(iMadzError("LOT_021", s"FOUP capacity exceeded: current $currentOccupancy + ${waferIds.size} new > 25"))

      // Validate wafers not already present in the lot
      val alreadyPresent = waferIds.intersect(state.waferIds)
      if (alreadyPresent.nonEmpty)
        return Left(iMadzError("LOT_022", s"Wafers $alreadyPresent already belong to this lot"))

      Right(List(WaferAdditionReserved(transferId, waferIds)))
    }
  }

  implicit object CommitAddWaferRule extends InvariantRule[LotEvent, LotState, Id] {
    def apply(state: LotState, param: Id): Either[iMadzError, List[LotEvent]] = {
      val transferId = param
      if (state.incomingWafers.contains(transferId))
        Right(List(WaferAdditionCommitted(transferId)))
      else if (state.completedTransferIds.contains(transferId))
        Right(Nil) // already committed — idempotent
      else
        Left(iMadzError("LOT_023", s"Transfer $transferId not found in incoming wafers"))
    }
  }

  implicit object CancelAddWaferRule extends InvariantRule[LotEvent, LotState, Id] {
    def apply(state: LotState, param: Id): Either[iMadzError, List[LotEvent]] = {
      val transferId = param
      if (state.incomingWafers.contains(transferId))
        Right(List(WaferAdditionCanceled(transferId)))
      else if (state.completedTransferIds.contains(transferId))
        Right(Nil) // already committed, cancel is a no-op — idempotent
      else
        Left(iMadzError("LOT_024", s"Transfer $transferId not found in incoming wafers"))
    }
  }

  implicit object SealLotRule extends InvariantRule[LotEvent, LotState, Unit] {
    def apply(state: LotState, param: Unit): Either[iMadzError, List[LotEvent]] = {
      if (state.phase != Active)
        Left(iMadzError("LOT_030", s"Cannot seal lot in phase ${state.phase}"))
      else
        Right(List(LotSealed()))
    }
  }
}
