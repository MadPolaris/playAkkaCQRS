package net.imadz.fab.domain

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import net.imadz.application.aggregates.WaferProtocol._
import net.imadz.domain.entities.WaferEntity
import net.imadz.domain.entities.WaferEntity.{Active, Scrapped, WaferCreated, WaferScrapped, WaferStatusChanged, WaferTransferCommitted, WaferTransferReleased, WaferTransferReserved}
import net.imadz.fab.saga.FabSagaTestConfig
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID

class WaferAggregateSpec extends ScalaTestWithActorTestKit(FabSagaTestConfig.testConfig)
  with AnyWordSpecLike with BeforeAndAfterEach {

  private val lotId = UUID.randomUUID()
  private val reworkLotId = UUID.randomUUID()
  private val waferId = UUID.randomUUID()

  private var waferTestKit = FabSagaTestConfig.createWaferTestKit(waferId)

  override def beforeEach(): Unit = {
    waferTestKit = FabSagaTestConfig.createWaferTestKit(UUID.randomUUID())
  }

  // ===================================================================
  // Scenario A1: Create Wafer
  // ===================================================================

  "Wafer aggregate" should {
    "create wafer assigned to lot" in {
      val result = waferTestKit.runCommand[WaferConfirmation](replyTo =>
        CreateWafer(lotId, replyTo))

      result.reply.error shouldBe None
      result.reply.status shouldBe Some(Active)
      result.reply.lotId shouldBe Some(lotId)

      result.events should have size 1
      result.events.head shouldBe a[WaferCreated]

      result.state.status shouldBe Active
      result.state.lotId shouldBe Some(lotId)
    }

    "reject double create" in {
      waferTestKit.runCommand[WaferConfirmation](replyTo =>
        CreateWafer(lotId, replyTo))

      val secondLotId = UUID.randomUUID()
      val result = waferTestKit.runCommand[WaferConfirmation](replyTo =>
        CreateWafer(secondLotId, replyTo))

      result.reply.error shouldBe defined
      result.reply.error.get.code shouldBe "WFR_001"
      result.events shouldBe empty
    }
  }

  // ===================================================================
  // Scenario B4: Reserve Transfer (Saga Prepare)
  // ===================================================================

  "ReserveTransfer" should {
    "reserve wafer for transfer to target lot" in {
      createWafer(lotId)
      val txId = UUID.randomUUID()

      val result = waferTestKit.runCommand[TransferConfirmation](replyTo =>
        ReserveTransfer(txId, reworkLotId, replyTo))

      result.reply.error shouldBe None
      result.events should contain(WaferTransferReserved(txId, reworkLotId))
      result.state.reservedTransfer shouldBe Some((txId, reworkLotId))
      result.state.lotId shouldBe Some(lotId) // unchanged until commit
      result.state.status shouldBe Active
    }

    "be idempotent on same transferId" in {
      createWafer(lotId)
      val txId = UUID.randomUUID()

      waferTestKit.runCommand[TransferConfirmation](replyTo =>
        ReserveTransfer(txId, reworkLotId, replyTo))

      val result2 = waferTestKit.runCommand[TransferConfirmation](replyTo =>
        ReserveTransfer(txId, reworkLotId, replyTo))

      result2.reply.error shouldBe None
      result2.events shouldBe empty
    }

    "reject reserve on scrapped wafer" in {
      createWafer(lotId)
      scrapWafer("rework maxed out")

      val txId = UUID.randomUUID()
      val result = waferTestKit.runCommand[TransferConfirmation](replyTo =>
        ReserveTransfer(txId, reworkLotId, replyTo))

      result.reply.error shouldBe defined
      result.reply.error.get.code shouldBe "WFR_010"
    }
  }

  // ===================================================================
  // Scenario B5: Commit Transfer (Saga Commit)
  // ===================================================================

  "CommitTransfer" should {
    "commit transfer to target lot" in {
      createWafer(lotId)
      val txId = UUID.randomUUID()

      waferTestKit.runCommand[TransferConfirmation](replyTo =>
        ReserveTransfer(txId, reworkLotId, replyTo))

      val result = waferTestKit.runCommand[TransferConfirmation](replyTo =>
        CommitTransfer(txId, reworkLotId, replyTo))

      result.reply.error shouldBe None
      result.events should contain(WaferTransferCommitted(txId, reworkLotId))
      result.state.lotId shouldBe Some(reworkLotId)
      result.state.reservedTransfer shouldBe None
      result.state.status shouldBe Active
    }

    "reject commit without prior reserve" in {
      createWafer(lotId)
      val txId = UUID.randomUUID()

      val result = waferTestKit.runCommand[TransferConfirmation](replyTo =>
        CommitTransfer(txId, reworkLotId, replyTo))

      result.reply.error shouldBe defined
      result.reply.error.get.code shouldBe "WFR_013"
    }
  }

  // ===================================================================
  // Compensate: Release Transfer
  // ===================================================================

  "ReleaseTransfer" should {
    "release reservation without changing lot" in {
      createWafer(lotId)
      val txId = UUID.randomUUID()

      waferTestKit.runCommand[TransferConfirmation](replyTo =>
        ReserveTransfer(txId, reworkLotId, replyTo))

      val result = waferTestKit.runCommand[TransferConfirmation](replyTo =>
        ReleaseTransfer(txId, replyTo))

      result.reply.error shouldBe None
      result.events should contain(WaferTransferReleased(txId))
      result.state.reservedTransfer shouldBe None
      result.state.lotId shouldBe Some(lotId) // unchanged
    }

    "reject release without prior reserve" in {
      createWafer(lotId)
      val txId = UUID.randomUUID()

      val result = waferTestKit.runCommand[TransferConfirmation](replyTo =>
        ReleaseTransfer(txId, replyTo))

      result.reply.error shouldBe defined
      result.reply.error.get.code shouldBe "WFR_014"
    }
  }

  // ===================================================================
  // Scenario C4: Scrap Wafer
  // ===================================================================

  "ScrapWafer" should {
    "scrap a wafer" in {
      createWafer(lotId)

      val result = waferTestKit.runCommand[WaferConfirmation](replyTo =>
        ScrapWafer("Max rework exceeded", replyTo))

      result.reply.error shouldBe None
      result.events should contain(WaferScrapped("Max rework exceeded"))
      result.state.status shouldBe Scrapped
    }

    "reject scrap of already scrapped wafer" in {
      createWafer(lotId)
      scrapWafer("first scrap")

      val result = waferTestKit.runCommand[WaferConfirmation](replyTo =>
        ScrapWafer("second scrap attempt", replyTo))

      result.reply.error shouldBe defined
      result.reply.error.get.code shouldBe "WFR_020"
      result.events shouldBe empty
    }
  }

  // ===================================================================
  // Change Status
  // ===================================================================

  "ChangeStatus" should {
    "change wafer status" in {
      createWafer(lotId)

      val result = waferTestKit.runCommand[WaferConfirmation](replyTo =>
        ChangeStatus(WaferEntity.OnHold, replyTo))

      result.reply.error shouldBe None
      result.events should contain(WaferStatusChanged(WaferEntity.OnHold))
      result.state.status shouldBe WaferEntity.OnHold
    }
  }

  "GetWaferState" should {
    "return current state" in {
      createWafer(lotId)

      val result = waferTestKit.runCommand[WaferConfirmation](replyTo =>
        GetWaferState(replyTo))

      result.reply.error shouldBe None
      result.reply.status shouldBe Some(Active)
      result.reply.lotId shouldBe Some(lotId)
    }
  }

  // ===================================================================
  // Helpers
  // ===================================================================

  private def createWafer(lotId: java.util.UUID): Unit = {
    val result = waferTestKit.runCommand[WaferConfirmation](replyTo =>
      CreateWafer(lotId, replyTo))
    require(result.reply.error.isEmpty, s"createWafer failed: ${result.reply.error}")
  }

  private def scrapWafer(reason: String): Unit = {
    val result = waferTestKit.runCommand[WaferConfirmation](replyTo =>
      ScrapWafer(reason, replyTo))
    require(result.reply.error.isEmpty, s"scrapWafer failed: ${result.reply.error}")
  }
}
