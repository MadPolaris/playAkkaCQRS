package net.imadz.fab.domain

import net.imadz.application.aggregates._

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import net.imadz.application.aggregates.LotProtocol._
import net.imadz.domain.entities.LotEntity
import net.imadz.domain.entities.LotEntity.{Active, LotCreated, LotSealed, LotState, Sealed, WaferAdditionCanceled, WaferAdditionCommitted, WaferAdditionReserved, WaferRemovalCommitted, WaferRemovalReleased, WaferRemovalReserved}
import net.imadz.fab.saga.FabSagaTestConfig
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID

class LotAggregateSpec extends ScalaTestWithActorTestKit(FabSagaTestConfig.testConfig)
  with AnyWordSpecLike with BeforeAndAfterEach {

  private val lotId = UUID.randomUUID()
  private val w1 = UUID.randomUUID()
  private val w2 = UUID.randomUUID()
  private val w3 = UUID.randomUUID()
  private val w4 = UUID.randomUUID()
  private val w5 = UUID.randomUUID()
  private val fiveWafers = Map(w1 -> "WAFER-1", w2 -> "WAFER-2", w3 -> "WAFER-3", w4 -> "WAFER-4", w5 -> "WAFER-5")
  private val fiveWafersSet = Set(w1, w2, w3, w4, w5)

  private var lotTestKit = FabSagaTestConfig.createLotTestKit(lotId)

  override def beforeEach(): Unit = {
    lotTestKit = FabSagaTestConfig.createLotTestKit(UUID.randomUUID())
  }

  // ===================================================================
  // Scenario A0: Create Lot
  // ===================================================================

  "Lot aggregate" should {
    "create lot with 5 wafers" in {
      val result = lotTestKit.runCommand[LotConfirmation](replyTo =>
        CreateLot("PHOTO-CELL-A", fiveWafers, replyTo))

      result.reply.error shouldBe None
      result.reply.waferIds shouldBe fiveWafersSet
      result.reply.phase shouldBe Some(Active)

      result.events should have size 1
      result.events.head shouldBe a[LotCreated]

      result.state.phase shouldBe Active
      result.state.waferIds shouldBe fiveWafersSet
      result.state.productId shouldBe "PHOTO-CELL-A"
    }

    "reject create on already created lot" in {
      // First create succeeds
      lotTestKit.runCommand[LotConfirmation](replyTo =>
        CreateLot("P1", Map(w1 -> "WAFER-1"), replyTo))

      // Second create must fail
      val result = lotTestKit.runCommand[LotConfirmation](replyTo =>
        CreateLot("P2", Map(w2 -> "WAFER-2"), replyTo))

      result.reply.error shouldBe defined
      result.reply.error.get.code shouldBe "LOT_001"
      result.events shouldBe empty
    }

    "reject lot with > 25 wafers" in {
      val tooMany = (1 to 26).map(i => UUID.randomUUID() -> s"WAFER-$i").toMap
      val result = lotTestKit.runCommand[LotConfirmation](replyTo =>
        CreateLot("P1", tooMany, replyTo))

      result.reply.error shouldBe defined
      result.reply.error.get.code shouldBe "LOT_004"
    }

    "allow empty lot for child lot creation" in {
      val result = lotTestKit.runCommand[LotConfirmation](replyTo =>
        CreateLot("P1", Map.empty, replyTo))

      result.reply.error shouldBe None
      result.reply.phase shouldBe Some(Active)
      result.state.waferIds shouldBe empty
    }
  }

  // ===================================================================
  // Scenario B2: Reserve Wafer Removal (source lot)
  // ===================================================================

  "ReserveWaferRemoval" should {
    "reserve wafers that belong to the lot" in {
      createLot(fiveWafers)

      val txId = UUID.randomUUID()
      val result = lotTestKit.runCommand[WaferRemovalConfirmation](replyTo =>
        ReserveWaferRemoval(txId, Set(w1, w3), Set("WAFER-1", "WAFER-3"), replyTo))

      result.reply.error shouldBe None
      result.events should contain(WaferRemovalReserved(txId, Set(w1, w3), Set("WAFER-1", "WAFER-3")))
      result.state.reservedWafers should contain key txId
      result.state.reservedWafers(txId) shouldBe Set(w1, w3)
      // wafers still in lot until committed
      result.state.waferIds should contain allOf (w1, w3)
    }

    "be idempotent on same transferId" in {
      createLot(fiveWafers)
      val txId = UUID.randomUUID()

      lotTestKit.runCommand[WaferRemovalConfirmation](replyTo =>
        ReserveWaferRemoval(txId, Set(w1), Set("WAFER-1"), replyTo))

      val result2 = lotTestKit.runCommand[WaferRemovalConfirmation](replyTo =>
        ReserveWaferRemoval(txId, Set(w1), Set("WAFER-1"), replyTo))

      result2.reply.error shouldBe None
      // No new events on idempotent call
      result2.events shouldBe empty
    }

    "reject wafer not in lot" in {
      createLot(fiveWafers)
      val txId = UUID.randomUUID()
      val unknown = UUID.randomUUID()

      val result = lotTestKit.runCommand[WaferRemovalConfirmation](replyTo =>
        ReserveWaferRemoval(txId, Set(unknown), Set("UNKNOWN"), replyTo))

      result.reply.error shouldBe defined
      result.reply.error.get.code shouldBe "LOT_011"
    }

    "reject reservation from different transfers on same wafer" in {
      createLot(fiveWafers)
      val t1 = UUID.randomUUID()
      val t2 = UUID.randomUUID()

      lotTestKit.runCommand[WaferRemovalConfirmation](replyTo =>
        ReserveWaferRemoval(t1, Set(w1), Set("WAFER-1"), replyTo))

      val result = lotTestKit.runCommand[WaferRemovalConfirmation](replyTo =>
        ReserveWaferRemoval(t2, Set(w1), Set("WAFER-1"), replyTo))

      result.reply.error shouldBe defined
      result.reply.error.get.code shouldBe "LOT_012"
    }
  }

  // ===================================================================
  // Scenario B6: Commit Wafer Removal
  // ===================================================================

  "CommitWaferRemoval" should {
    "remove reserved wafers from lot" in {
      createLot(fiveWafers)
      val txId = UUID.randomUUID()

      lotTestKit.runCommand[WaferRemovalConfirmation](replyTo =>
        ReserveWaferRemoval(txId, Set(w3), Set("WAFER-3"), replyTo))

      val result = lotTestKit.runCommand[WaferRemovalConfirmation](replyTo =>
        CommitWaferRemoval(txId, replyTo))

      result.reply.error shouldBe None
      result.events should contain(WaferRemovalCommitted(txId, Set("WAFER-3")))
      result.state.waferIds should not contain w3
      result.state.waferIds should have size 4
      result.state.reservedWafers should not contain key(txId)
    }

    "reject commit for unknown transferId" in {
      createLot(fiveWafers)
      val unknownTx = UUID.randomUUID()

      val result = lotTestKit.runCommand[WaferRemovalConfirmation](replyTo =>
        CommitWaferRemoval(unknownTx, replyTo))

      result.reply.error shouldBe defined
      result.reply.error.get.code shouldBe "LOT_013"
    }

    "auto-seal child lot when all wafers are removed" in {
      val childTestKit = FabSagaTestConfig.createLotTestKit(UUID.randomUUID())
      val parentId = UUID.randomUUID()
      val childWafers = Map(w1 -> "WAFER-1", w2 -> "WAFER-2")

      childTestKit.runCommand[LotConfirmation](r =>
        CreateLot("CHILD-PROD", childWafers, r,
          parentLotId = Some(parentId), splitReason = Some(LotEntity.ReworkSplit)))

      childTestKit.getState().phase shouldBe Active

      val txId = UUID.randomUUID()
      childTestKit.runCommand[WaferRemovalConfirmation](r =>
        ReserveWaferRemoval(txId, Set(w1, w2), Set("WAFER-1", "WAFER-2"), r))

      val result = childTestKit.runCommand[WaferRemovalConfirmation](r =>
        CommitWaferRemoval(txId, r))

      result.reply.error shouldBe None
      result.state.waferIds shouldBe empty
      result.state.phase shouldBe Sealed
    }
  }

  // ===================================================================
  // Compensate: Release Reserved Wafer
  // ===================================================================

  "ReleaseReservedWafer" should {
    "release reservation without removing wafers" in {
      createLot(fiveWafers)
      val txId = UUID.randomUUID()

      lotTestKit.runCommand[WaferRemovalConfirmation](replyTo =>
        ReserveWaferRemoval(txId, Set(w1), Set("WAFER-1"), replyTo))

      val result = lotTestKit.runCommand[WaferRemovalConfirmation](replyTo =>
        ReleaseReservedWafer(txId, replyTo))

      result.reply.error shouldBe None
      result.events should contain(WaferRemovalReleased(txId))
      result.state.reservedWafers should not contain key(txId)
      result.state.waferIds should contain(w1) // wafer stays
    }

    "treat release for unknown transferId as idempotent success (LOT_014 compensation is best-effort)" in {
      createLot(fiveWafers)
      val unknownTx = UUID.randomUUID()

      val result = lotTestKit.runCommand[WaferRemovalConfirmation](replyTo =>
        ReleaseReservedWafer(unknownTx, replyTo))

      result.reply.error shouldBe None
      result.events shouldBe empty // no event persisted — nothing to release
      result.state.waferIds should have size 5
    }
  }

  // ===================================================================
  // Scenario B3: Reserve Add Wafer (target lot)
  // ===================================================================

  "ReserveAddWafer" should {
    "reserve incoming wafers" in {
      createLot(fiveWafers)
      val txId = UUID.randomUUID()
      val newWafer = UUID.randomUUID()

      val result = lotTestKit.runCommand[WaferAdditionConfirmation](replyTo =>
        ReserveAddWafer(txId, Set(newWafer), replyTo))

      result.reply.error shouldBe None
      result.events should contain(WaferAdditionReserved(txId, Set(newWafer)))
      result.state.incomingWafers should contain key txId
      result.state.waferIds should not contain newWafer // not yet added
    }

    "reject when FOUP total would exceed 25" in {
      val manyWafers = (1 to 25).map(_ => UUID.randomUUID()).toSet
      createLot(Map(w1 -> "WAFER-1")) // 1 existing
      val txId = UUID.randomUUID()

      val result = lotTestKit.runCommand[WaferAdditionConfirmation](replyTo =>
        ReserveAddWafer(txId, manyWafers, replyTo))

      result.reply.error shouldBe defined
      result.reply.error.get.code shouldBe "LOT_021"
    }

    "reject wafers already in lot" in {
      createLot(fiveWafers)
      val txId = UUID.randomUUID()

      val result = lotTestKit.runCommand[WaferAdditionConfirmation](replyTo =>
        ReserveAddWafer(txId, Set(w1), replyTo))

      result.reply.error shouldBe defined
      result.reply.error.get.code shouldBe "LOT_022"
    }

    "be idempotent on same transferId" in {
      createLot(fiveWafers)
      val txId = UUID.randomUUID()
      val newWafer = UUID.randomUUID()

      lotTestKit.runCommand[WaferAdditionConfirmation](replyTo =>
        ReserveAddWafer(txId, Set(newWafer), replyTo))

      val result2 = lotTestKit.runCommand[WaferAdditionConfirmation](replyTo =>
        ReserveAddWafer(txId, Set(newWafer), replyTo))

      result2.reply.error shouldBe None
      result2.events shouldBe empty
    }

    "carry wafer state through reserve+commit so merge-back keeps classification" in {
      createLot(fiveWafers) // phase Active (accepts additions)
      val txId = UUID.randomUUID()
      val returningWafer = UUID.randomUUID()
      // Snapshot taken from the child lot at reserve time — the wafer earned
      // PASS + a CD value while away; the merge must not wipe it to Pending
      val carried = Map(returningWafer -> LotEntity.WaferState(
        name = "PILOT-WAFER-1", classification = Some("PASS"),
        reworkCount = 1, cdValue = Some(31.5), measured = true))

      val reserved = lotTestKit.runCommand[WaferAdditionConfirmation](replyTo =>
        ReserveAddWafer(txId, Set(returningWafer), replyTo, carried))
      reserved.reply.error shouldBe None
      reserved.events should contain(WaferAdditionReserved(txId, Set(returningWafer), carried))

      val committed = lotTestKit.runCommand[WaferAdditionConfirmation](replyTo =>
        CommitAddWafer(txId, replyTo))
      committed.reply.error shouldBe None
      val ws = committed.state.wafers(returningWafer)
      ws.classification shouldBe Some("PASS")
      ws.reworkCount shouldBe 1
      ws.cdValue shouldBe Some(31.5)
      ws.measured shouldBe true
      ws.name shouldBe "PILOT-WAFER-1"
      committed.state.incomingCarriedWafers shouldBe empty // stash cleaned up
    }

    "add brand-new wafers with fresh state when no carried snapshot exists" in {
      createLot(fiveWafers)
      val txId = UUID.randomUUID()
      val newWafer = UUID.randomUUID()

      lotTestKit.runCommand[WaferAdditionConfirmation](replyTo =>
        ReserveAddWafer(txId, Set(newWafer), replyTo))
      val committed = lotTestKit.runCommand[WaferAdditionConfirmation](replyTo =>
        CommitAddWafer(txId, replyTo))

      val ws = committed.state.wafers(newWafer)
      ws.classification shouldBe None
      ws.measured shouldBe false
    }
  }

  // ===================================================================
  // Scenario B7: Commit Add Wafer
  // ===================================================================

  "CommitAddWafer" should {
    "add reserved incoming wafers to lot" in {
      createLot(fiveWafers)
      val txId = UUID.randomUUID()
      val newWafer = UUID.randomUUID()

      lotTestKit.runCommand[WaferAdditionConfirmation](replyTo =>
        ReserveAddWafer(txId, Set(newWafer), replyTo))

      val result = lotTestKit.runCommand[WaferAdditionConfirmation](replyTo =>
        CommitAddWafer(txId, replyTo))

      result.reply.error shouldBe None
      result.events should contain(WaferAdditionCommitted(txId))
      result.state.waferIds should contain(newWafer)
      result.state.incomingWafers should not contain key(txId)
      result.state.waferIds should have size 6 // 5 original + 1 new
    }

    "reject commit for unknown transferId" in {
      createLot(fiveWafers)
      val unknownTx = UUID.randomUUID()

      val result = lotTestKit.runCommand[WaferAdditionConfirmation](replyTo =>
        CommitAddWafer(unknownTx, replyTo))

      result.reply.error shouldBe defined
      result.reply.error.get.code shouldBe "LOT_023"
    }
  }

  // ===================================================================
  // Compensate: Cancel Add Wafer
  // ===================================================================

  "CancelAddWafer" should {
    "cancel incoming reservation" in {
      createLot(fiveWafers)
      val txId = UUID.randomUUID()
      val newWafer = UUID.randomUUID()

      lotTestKit.runCommand[WaferAdditionConfirmation](replyTo =>
        ReserveAddWafer(txId, Set(newWafer), replyTo))

      val result = lotTestKit.runCommand[WaferAdditionConfirmation](replyTo =>
        CancelAddWafer(txId, replyTo))

      result.reply.error shouldBe None
      result.events should contain(WaferAdditionCanceled(txId))
      result.state.incomingWafers should not contain key(txId)
      result.state.waferIds should not contain newWafer
    }
  }

  // ===================================================================
  // Idempotency: Repeat commit/release after already completed
  // ===================================================================

  "CommitWaferRemoval idempotency" should {
    "handle repeat removal commit (no new events)" in {
      createLot(fiveWafers)
      val txId = UUID.randomUUID()
      lotTestKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(txId, Set(w3), Set("WAFER-3"), r))
      lotTestKit.runCommand[WaferRemovalConfirmation](r => CommitWaferRemoval(txId, r))

      // Repeat commit — idempotent
      val result = lotTestKit.runCommand[WaferRemovalConfirmation](r => CommitWaferRemoval(txId, r))
      result.reply.error shouldBe None
      result.events shouldBe empty // no new events
      result.state.waferIds should not contain w3
    }
  }

  "CommitAddWafer idempotency" should {
    "handle repeat addition commit (no new events)" in {
      createLot(fiveWafers)
      val newWafer = UUID.randomUUID()
      val txId = UUID.randomUUID()
      lotTestKit.runCommand[WaferAdditionConfirmation](r => ReserveAddWafer(txId, Set(newWafer), r))
      lotTestKit.runCommand[WaferAdditionConfirmation](r => CommitAddWafer(txId, r))

      // Repeat commit — idempotent
      val result = lotTestKit.runCommand[WaferAdditionConfirmation](r => CommitAddWafer(txId, r))
      result.reply.error shouldBe None
      result.events shouldBe empty // no new events
    }
  }

  "ReleaseReservedWafer idempotency" should {
    "handle release after already committed (no-op)" in {
      createLot(fiveWafers)
      val txId = UUID.randomUUID()
      lotTestKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(txId, Set(w1), Set("WAFER-1"), r))
      lotTestKit.runCommand[WaferRemovalConfirmation](r => CommitWaferRemoval(txId, r))

      // Release after commit — idempotent no-op
      val result = lotTestKit.runCommand[WaferRemovalConfirmation](r => ReleaseReservedWafer(txId, r))
      result.reply.error shouldBe None
      result.events shouldBe empty
    }
  }

  "CancelAddWafer idempotency" should {
    "handle cancel after already committed (no-op)" in {
      createLot(fiveWafers)
      val newWafer = UUID.randomUUID()
      val txId = UUID.randomUUID()
      lotTestKit.runCommand[WaferAdditionConfirmation](r => ReserveAddWafer(txId, Set(newWafer), r))
      lotTestKit.runCommand[WaferAdditionConfirmation](r => CommitAddWafer(txId, r))

      // Cancel after commit — idempotent no-op
      val result = lotTestKit.runCommand[WaferAdditionConfirmation](r => CancelAddWafer(txId, r))
      result.reply.error shouldBe None
      result.events shouldBe empty
    }
  }

  // ===================================================================
  // Scenario A4: Seal Lot
  // ===================================================================

  "SealLot" should {
    "seal an active lot" in {
      createLot(fiveWafers)

      val result = lotTestKit.runCommand[LotConfirmation](replyTo =>
        SealLot(replyTo))

      result.reply.error shouldBe None
      result.events should contain(LotSealed())
      result.state.phase shouldBe LotEntity.Sealed
    }
  }

  "GetLotState" should {
    "return current lot state" in {
      createLot(fiveWafers)

      val result = lotTestKit.runCommand[LotConfirmation](replyTo =>
        GetLotState(replyTo))

      result.reply.error shouldBe None
      result.reply.waferIds shouldBe fiveWafersSet
      result.reply.phase shouldBe Some(Active)
    }
  }

  // ===================================================================
  // Helpers
  // ===================================================================

  private def createLot(wafers: Map[java.util.UUID, String]): Unit = {
    val result = lotTestKit.runCommand[LotConfirmation](replyTo =>
      CreateLot("TEST-PRODUCT", wafers, replyTo))
    require(result.reply.error.isEmpty, s"createLot failed: ${result.reply.error}")
  }
}
