package net.imadz.fab.domain

import net.imadz.application.aggregates._

import net.imadz.common.CommonTypes.iMadzError
import net.imadz.domain.entities.LotEntity
import net.imadz.domain.entities.LotEntity._
import net.imadz.domain.invariants.LotInvariants
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID

class LotInvariantSpec extends AnyWordSpec with Matchers {

  val lotId = UUID.randomUUID()
  val wafer1 = UUID.randomUUID()
  val wafer2 = UUID.randomUUID()
  val activeState = LotState(lotId, "PROD-A", Map(wafer1 -> WaferState("WAFER-1"), wafer2 -> WaferState("WAFER-2")), Map.empty, Map.empty, phase = Active)

  // --- CreateLot Rule ---
  "CreateLotRule" should {
    "accept valid create request" in {
      val waferMap = Map(wafer1 -> "WAFER-1")
      val result = LotInvariants.CreateLotRule(LotEntity.empty(lotId), ("PROD-A", waferMap, None, None, None))
      result.map(_.head) shouldBe Right(LotCreated("PROD-A", waferMap))
    }

    "reject empty productId" in {
      val waferMap = Map(wafer1 -> "WAFER-1")
      val result = LotInvariants.CreateLotRule(LotEntity.empty(lotId), ("", waferMap, None, None, None))
      result shouldBe Left(iMadzError("LOT_002", "Product ID must not be empty"))
    }

    "reject if lot already created" in {
      val waferMap = Map(wafer1 -> "WAFER-1")
      val result = LotInvariants.CreateLotRule(activeState, ("PROD-B", waferMap, None, None, None))
      result shouldBe Left(iMadzError("LOT_001", s"Lot $lotId already created, cannot create again"))
    }

    "allow empty wafer list for child lot creation" in {
      val result = LotInvariants.CreateLotRule(LotEntity.empty(lotId), ("PROD-A", Map.empty, None, None, None))
      result shouldBe Right(List(LotCreated("PROD-A", Map.empty)))
    }

    "allow child lot creation with parentLotId and splitReason" in {
      val parentId = UUID.randomUUID()
      val result = LotInvariants.CreateLotRule(LotEntity.empty(lotId), ("PROD-A", Map.empty, Some(parentId), Some(ReworkSplit), None))
      result shouldBe Right(List(LotCreated("PROD-A", Map.empty, Some(parentId), Some(ReworkSplit), None)))
    }

    "reject > 25 wafers" in {
      val tooMany = (1 to 26).map(i => UUID.randomUUID() -> s"WAFER-$i").toMap
      val result = LotInvariants.CreateLotRule(LotEntity.empty(lotId), ("PROD-A", tooMany, None, None, None))
      result.isLeft shouldBe true
      result.left.get.code shouldBe "LOT_004"
    }
  }

  // --- ReserveWaferRemoval Rule ---
  "ReserveWaferRemovalRule" should {
    "reserve wafers that belong to the lot" in {
      val transferId = UUID.randomUUID()
      val result = LotInvariants.ReserveWaferRemovalRule(activeState, (transferId, Set(wafer1), Set("WAFER-1")))
      result shouldBe Right(List(WaferRemovalReserved(transferId, Set(wafer1), Set("WAFER-1"))))
    }

    "be idempotent on same transferId" in {
      val transferId = UUID.randomUUID()
      val state = activeState.copy(reservedWafers = Map(transferId -> Set(wafer1)))
      val result = LotInvariants.ReserveWaferRemovalRule(state, (transferId, Set(wafer1), Set("WAFER-1")))
      result shouldBe Right(Nil)
    }

    "reject wafers not in lot" in {
      val transferId = UUID.randomUUID()
      val unknownWafer = UUID.randomUUID()
      val result = LotInvariants.ReserveWaferRemovalRule(activeState, (transferId, Set(unknownWafer), Set("UNKNOWN")))
      result.isLeft shouldBe true
      result.left.get.code shouldBe "LOT_011"
    }

    "reject if lot not active" in {
      val transferId = UUID.randomUUID()
      val emptyState = LotEntity.empty(lotId)
      val result = LotInvariants.ReserveWaferRemovalRule(emptyState, (transferId, Set(wafer1), Set("WAFER-1")))
      result.isLeft shouldBe true
      result.left.get.code shouldBe "LOT_010"
    }

    "reject double reservation from different transfers" in {
      val t1 = UUID.randomUUID()
      val t2 = UUID.randomUUID()
      val state = activeState.copy(reservedWafers = Map(t1 -> Set(wafer1)))
      val result = LotInvariants.ReserveWaferRemovalRule(state, (t2, Set(wafer1), Set("WAFER-1")))
      result.isLeft shouldBe true
      result.left.get.code shouldBe "LOT_012"
    }
  }

  // --- ReserveAddWafer Rule ---
  "ReserveAddWaferRule" should {
    "accept incoming wafer for active lot" in {
      val transferId = UUID.randomUUID()
      val newWafer = UUID.randomUUID()
      val result = LotInvariants.ReserveAddWaferRule(activeState, (transferId, Set(newWafer)))
      result shouldBe Right(List(WaferAdditionReserved(transferId, Set(newWafer))))
    }

    "reject when FOUP would exceed 25" in {
      val transferId = UUID.randomUUID()
      val manyWafers = (1 to 24).map(_ => UUID.randomUUID()).toSet
      val result = LotInvariants.ReserveAddWaferRule(activeState, (transferId, manyWafers))
      result.isLeft shouldBe true
      result.left.get.code shouldBe "LOT_021"
    }

    "be idempotent on same transferId" in {
      val transferId = UUID.randomUUID()
      val newWafer = UUID.randomUUID()
      val state = activeState.copy(incomingWafers = Map(transferId -> Set(newWafer)))
      val result = LotInvariants.ReserveAddWaferRule(state, (transferId, Set(newWafer)))
      result shouldBe Right(Nil)
    }

    "reject wafers already present in lot" in {
      val transferId = UUID.randomUUID()
      val result = LotInvariants.ReserveAddWaferRule(activeState, (transferId, Set(wafer1)))
      result.isLeft shouldBe true
      result.left.get.code shouldBe "LOT_022"
    }
  }

}
