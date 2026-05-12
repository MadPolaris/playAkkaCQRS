package net.imadz.fab.domain

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import net.imadz.application.aggregates.LotProtocol._
import net.imadz.domain.entities.LotEntity
import net.imadz.domain.entities.LotEntity.{Active, LotCreated, LotSealed, LotState, WaferAdditionCanceled, WaferAdditionCommitted, WaferAdditionReserved, WaferRemovalCommitted, WaferRemovalReleased, WaferRemovalReserved}
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
  private val fiveWafers = Set(w1, w2, w3, w4, w5)

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
      result.reply.waferIds shouldBe fiveWafers
      result.reply.phase shouldBe Some(Active)

      result.events should have size 1
      result.events.head shouldBe a[LotCreated]

      result.state.phase shouldBe Active
      result.state.waferIds shouldBe fiveWafers
      result.state.productId shouldBe "PHOTO-CELL-A"
    }

    "reject create on already created lot" in {
      // First create succeeds
      lotTestKit.runCommand[LotConfirmation](replyTo =>
        CreateLot("P1", Set(w1), replyTo))

      // Second create must fail
      val result = lotTestKit.runCommand[LotConfirmation](replyTo =>
        CreateLot("P2", Set(w2), replyTo))

      result.reply.error shouldBe defined
      result.reply.error.get.code shouldBe "LOT_001"
      result.events shouldBe empty
    }

    "reject lot with > 25 wafers" in {
      val tooMany = (1 to 26).map(_ => UUID.randomUUID()).toSet
      val result = lotTestKit.runCommand[LotConfirmation](replyTo =>
        CreateLot("P1", tooMany, replyTo))

      result.reply.error shouldBe defined
      result.reply.error.get.code shouldBe "LOT_004"
    }

    "reject lot with empty wafer list" in {
      val result = lotTestKit.runCommand[LotConfirmation](replyTo =>
        CreateLot("P1", Set.empty, replyTo))

      result.reply.error shouldBe defined
      result.reply.error.get.code shouldBe "LOT_003"
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
        ReserveWaferRemoval(txId, Set(w1, w3), replyTo))

      result.reply.error shouldBe None
      result.events should contain(WaferRemovalReserved(txId, Set(w1, w3)))
      result.state.reservedWafers should contain key txId
      result.state.reservedWafers(txId) shouldBe Set(w1, w3)
      // wafers still in lot until committed
      result.state.waferIds should contain allOf (w1, w3)
    }

    "be idempotent on same transferId" in {
      createLot(fiveWafers)
      val txId = UUID.randomUUID()

      lotTestKit.runCommand[WaferRemovalConfirmation](replyTo =>
        ReserveWaferRemoval(txId, Set(w1), replyTo))

      val result2 = lotTestKit.runCommand[WaferRemovalConfirmation](replyTo =>
        ReserveWaferRemoval(txId, Set(w1), replyTo))

      result2.reply.error shouldBe None
      // No new events on idempotent call
      result2.events shouldBe empty
    }

    "reject wafer not in lot" in {
      createLot(fiveWafers)
      val txId = UUID.randomUUID()
      val unknown = UUID.randomUUID()

      val result = lotTestKit.runCommand[WaferRemovalConfirmation](replyTo =>
        ReserveWaferRemoval(txId, Set(unknown), replyTo))

      result.reply.error shouldBe defined
      result.reply.error.get.code shouldBe "LOT_011"
    }

    "reject reservation from different transfers on same wafer" in {
      createLot(fiveWafers)
      val t1 = UUID.randomUUID()
      val t2 = UUID.randomUUID()

      lotTestKit.runCommand[WaferRemovalConfirmation](replyTo =>
        ReserveWaferRemoval(t1, Set(w1), replyTo))

      val result = lotTestKit.runCommand[WaferRemovalConfirmation](replyTo =>
        ReserveWaferRemoval(t2, Set(w1), replyTo))

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
        ReserveWaferRemoval(txId, Set(w3), replyTo))

      val result = lotTestKit.runCommand[WaferRemovalConfirmation](replyTo =>
        CommitWaferRemoval(txId, replyTo))

      result.reply.error shouldBe None
      result.events should contain(WaferRemovalCommitted(txId))
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
  }

  // ===================================================================
  // Compensate: Release Reserved Wafer
  // ===================================================================

  "ReleaseReservedWafer" should {
    "release reservation without removing wafers" in {
      createLot(fiveWafers)
      val txId = UUID.randomUUID()

      lotTestKit.runCommand[WaferRemovalConfirmation](replyTo =>
        ReserveWaferRemoval(txId, Set(w1), replyTo))

      val result = lotTestKit.runCommand[WaferRemovalConfirmation](replyTo =>
        ReleaseReservedWafer(txId, replyTo))

      result.reply.error shouldBe None
      result.events should contain(WaferRemovalReleased(txId))
      result.state.reservedWafers should not contain key(txId)
      result.state.waferIds should contain(w1) // wafer stays
    }

    "reject release for unknown transferId" in {
      createLot(fiveWafers)
      val unknownTx = UUID.randomUUID()

      val result = lotTestKit.runCommand[WaferRemovalConfirmation](replyTo =>
        ReleaseReservedWafer(unknownTx, replyTo))

      result.reply.error shouldBe defined
      result.reply.error.get.code shouldBe "LOT_014"
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
      val manyWafers = (1 to 24).map(_ => UUID.randomUUID()).toSet
      createLot(Set(w1)) // 1 existing
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
      result.reply.waferIds shouldBe fiveWafers
      result.reply.phase shouldBe Some(Active)
    }
  }

  // ===================================================================
  // Helpers
  // ===================================================================

  private def createLot(wafers: Set[java.util.UUID]): Unit = {
    val result = lotTestKit.runCommand[LotConfirmation](replyTo =>
      CreateLot("TEST-PRODUCT", wafers, replyTo))
    require(result.reply.error.isEmpty, s"createLot failed: ${result.reply.error}")
  }
}
