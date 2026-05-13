package net.imadz.fab.domain

import net.imadz.domain.entities.WaferEntity._
import net.imadz.domain.invariants.WaferInvariants
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID

class WaferHoldReleaseInvariantSpec extends AnyWordSpec with Matchers {

  val waferId = UUID.randomUUID()
  val lotId = UUID.randomUUID()
  val activeState = WaferState(waferId, Some(lotId), Active, None)

  // ===================================================================
  // PlaceHoldRule
  // ===================================================================

  "PlaceHoldRule" should {
    "place active wafer on hold" in {
      val result = WaferInvariants.PlaceHoldRule(activeState, "borderline CD measurement")
      result shouldBe Right(List(WaferHoldPlaced("borderline CD measurement")))
    }

    "reject if wafer already on hold" in {
      val heldState = activeState.copy(status = OnHold)
      val result = WaferInvariants.PlaceHoldRule(heldState, "double hold attempt")
      result.isLeft shouldBe true
      result.left.get.code shouldBe "WFR_030"
    }

    "reject if wafer is scrapped" in {
      val scrappedState = activeState.copy(status = Scrapped)
      val result = WaferInvariants.PlaceHoldRule(scrappedState, "hold scrapped")
      result.isLeft shouldBe true
      result.left.get.code shouldBe "WFR_031"
    }
  }

  // ===================================================================
  // ReleaseHoldRule
  // ===================================================================

  "ReleaseHoldRule" should {
    "release wafer from hold" in {
      val heldState = activeState.copy(status = OnHold)
      val result = WaferInvariants.ReleaseHoldRule(heldState, ())
      result shouldBe Right(List(WaferHoldReleased()))
    }

    "reject if wafer is not on hold" in {
      val result = WaferInvariants.ReleaseHoldRule(activeState, ())
      result.isLeft shouldBe true
      result.left.get.code shouldBe "WFR_032"
    }

    "reject if wafer is scrapped" in {
      val scrappedState = activeState.copy(status = Scrapped)
      val result = WaferInvariants.ReleaseHoldRule(scrappedState, ())
      result.isLeft shouldBe true
      result.left.get.code shouldBe "WFR_032"
    }

    "reject if wafer is skipped" in {
      val skippedState = activeState.copy(status = Skipped)
      val result = WaferInvariants.ReleaseHoldRule(skippedState, ())
      result.isLeft shouldBe true
      result.left.get.code shouldBe "WFR_032"
    }
  }

  // ===================================================================
  // SkipWaferRule
  // ===================================================================

  "SkipWaferRule" should {
    "skip active wafer" in {
      val result = WaferInvariants.SkipWaferRule(activeState, "sampling skip")
      result shouldBe Right(List(WaferSkipped("sampling skip")))
    }

    "reject if already skipped" in {
      val skippedState = activeState.copy(status = Skipped)
      val result = WaferInvariants.SkipWaferRule(skippedState, "double skip")
      result.isLeft shouldBe true
      result.left.get.code shouldBe "WFR_040"
    }

    "reject if wafer is scrapped" in {
      val scrappedState = activeState.copy(status = Scrapped)
      val result = WaferInvariants.SkipWaferRule(scrappedState, "skip scrapped")
      result.isLeft shouldBe true
      result.left.get.code shouldBe "WFR_041"
    }
  }

  // ===================================================================
  // Hold + Skip interaction
  // ===================================================================

  "Hold-Skip interaction" should {
    "not allow placing on hold a skipped wafer" in {
      val skippedState = activeState.copy(status = Skipped)
      // PlaceHold only rejects OnHold and Scrapped, Skipped should pass
      val result = WaferInvariants.PlaceHoldRule(skippedState, "hold after skip")
      result shouldBe Right(List(WaferHoldPlaced("hold after skip")))
    }
  }
}
