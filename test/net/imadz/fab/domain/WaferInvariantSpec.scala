package net.imadz.fab.domain

import net.imadz.domain.entities.WaferEntity._
import net.imadz.domain.invariants.WaferInvariants
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID

class WaferInvariantSpec extends AnyWordSpec with Matchers {

  val waferId = UUID.randomUUID()
  val lotId = UUID.randomUUID()
  val emptyState = WaferState(waferId, None, Created, None)
  val activeState = WaferState(waferId, Some(lotId), Active, None)

  "CreateWaferRule" should {
    "create wafer with assigned lot" in {
      val result = WaferInvariants.CreateWaferRule(emptyState, lotId)
      result shouldBe Right(List(WaferCreated(lotId)))
    }

    "reject if already assigned" in {
      val result = WaferInvariants.CreateWaferRule(activeState, lotId)
      result.isLeft shouldBe true
      result.left.get.code shouldBe "WFR_001"
    }
  }

  "ReserveTransferRule" should {
    "reserve transfer for active wafer" in {
      val transferId = UUID.randomUUID()
      val targetLotId = UUID.randomUUID()
      val result = WaferInvariants.ReserveTransferRule(activeState, (transferId, targetLotId))
      result shouldBe Right(List(WaferTransferReserved(transferId, targetLotId)))
    }

    "be idempotent on same transferId" in {
      val transferId = UUID.randomUUID()
      val targetLotId = UUID.randomUUID()
      val state = activeState.copy(reservedTransfer = Some((transferId, targetLotId)))
      val result = WaferInvariants.ReserveTransferRule(state, (transferId, targetLotId))
      result shouldBe Right(Nil)
    }

    "reject transfer for scrapped wafer" in {
      val transferId = UUID.randomUUID()
      val targetLotId = UUID.randomUUID()
      val scrappedState = activeState.copy(status = Scrapped)
      val result = WaferInvariants.ReserveTransferRule(scrappedState, (transferId, targetLotId))
      result.isLeft shouldBe true
      result.left.get.code shouldBe "WFR_010"
    }

    "reject transfer for wafer not assigned to any lot" in {
      val transferId = UUID.randomUUID()
      val targetLotId = UUID.randomUUID()
      val result = WaferInvariants.ReserveTransferRule(emptyState, (transferId, targetLotId))
      result.isLeft shouldBe true
      result.left.get.code shouldBe "WFR_011"
    }
  }

  "CommitTransferRule" should {
    "commit when reserved transfer exists" in {
      val transferId = UUID.randomUUID()
      val targetLotId = UUID.randomUUID()
      val state = activeState.copy(reservedTransfer = Some((transferId, targetLotId)))
      val result = WaferInvariants.CommitTransferRule(state, (transferId, targetLotId))
      result shouldBe Right(List(WaferTransferCommitted(transferId, targetLotId)))
    }

    "reject when no reserved transfer" in {
      val transferId = UUID.randomUUID()
      val targetLotId = UUID.randomUUID()
      val result = WaferInvariants.CommitTransferRule(activeState, (transferId, targetLotId))
      result.isLeft shouldBe true
      result.left.get.code shouldBe "WFR_013"
    }
  }

  "ReleaseTransferRule" should {
    "release reserved transfer" in {
      val transferId = UUID.randomUUID()
      val targetLotId = UUID.randomUUID()
      val state = activeState.copy(reservedTransfer = Some((transferId, targetLotId)))
      val result = WaferInvariants.ReleaseTransferRule(state, transferId)
      result shouldBe Right(List(WaferTransferReleased(transferId)))
    }
  }

  "ScrapWaferRule" should {
    "scrap an active wafer" in {
      val result = WaferInvariants.ScrapWaferRule(activeState, "defect detected")
      result shouldBe Right(List(WaferScrapped("defect detected")))
    }

    "reject if already scrapped" in {
      val scrappedState = activeState.copy(status = Scrapped)
      val result = WaferInvariants.ScrapWaferRule(scrappedState, "double scrap")
      result.isLeft shouldBe true
      result.left.get.code shouldBe "WFR_020"
    }
  }
}
