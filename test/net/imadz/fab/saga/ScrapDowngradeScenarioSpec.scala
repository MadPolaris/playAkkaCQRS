package net.imadz.fab.saga

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import net.imadz.application.aggregates.LotProtocol._
import net.imadz.application.aggregates.WaferProtocol._
import net.imadz.domain.entities.LotEntity.{Active => LotActive, _}
import net.imadz.domain.entities.WaferEntity.{Active => WaferActive, Scrapped, _}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID

/**
 * Integration test for Scrap & Downgrade scenario:
 *   Wafer is scrapped directly without creating a child lot.
 *
 * Flow: Create Lot(3 wafers) → Measure → Classify → Scrap 1 wafer
 * No split, no merge. Just status change Active → Scrapped.
 */
class ScrapDowngradeScenarioSpec extends ScalaTestWithActorTestKit(FabSagaTestConfig.testConfig)
  with AnyWordSpecLike with BeforeAndAfterAll {

  private val sourceLotId = UUID.nameUUIDFromBytes("scrap-source".getBytes)
  private val w1 = UUID.nameUUIDFromBytes("SCRAP-W1".getBytes)
  private val w2 = UUID.nameUUIDFromBytes("SCRAP-W2".getBytes)
  private val w3 = UUID.nameUUIDFromBytes("SCRAP-W3".getBytes)
  private val threeWafers = Set(w1, w2, w3)

  // ===================================================================
  // Happy Path: Scrap 1 wafer, no child lot
  // ===================================================================

  "Scrap & Downgrade" should {
    "scrap one wafer without creating a child lot" in {
      val sKit = FabSagaTestConfig.createLotTestKit(sourceLotId)
      val w1Kit = FabSagaTestConfig.createWaferTestKit(w1)

      // Create source lot with 3 wafers
      sKit.runCommand[LotConfirmation](r => CreateLot("SCRAP-PROD", threeWafers, r))
      w1Kit.runCommand[WaferConfirmation](r => CreateWafer(sourceLotId, r))

      // Scrap W1 after detecting fatal defect
      val scrapResult = w1Kit.runCommand[WaferConfirmation](r =>
        ScrapWafer("particle contamination detected", r))

      scrapResult.reply.error shouldBe None
      scrapResult.events should contain(WaferScrapped("particle contamination detected"))
      scrapResult.state.status shouldBe Scrapped

      // No child lot was created — the lot still holds the wafer count
      // (in practice, scrapped wafers would be removed from lot by a separate process)
    }

    "reject double scrap on same wafer" in {
      val w2Kit = FabSagaTestConfig.createWaferTestKit(w2)

      w2Kit.runCommand[WaferConfirmation](r => CreateWafer(sourceLotId, r))
      w2Kit.runCommand[WaferConfirmation](r => ScrapWafer("first scrap", r))

      val second = w2Kit.runCommand[WaferConfirmation](r =>
        ScrapWafer("second scrap attempt", r))

      second.reply.error shouldBe defined
      second.reply.error.get.code shouldBe "WFR_020"
    }

    "reject transfer on scrapped wafer" in {
      val w3Kit = FabSagaTestConfig.createWaferTestKit(w3)
      w3Kit.runCommand[WaferConfirmation](r => CreateWafer(sourceLotId, r))
      w3Kit.runCommand[WaferConfirmation](r => ScrapWafer("defect", r))

      val txId = UUID.randomUUID()
      val targetLotId = UUID.randomUUID()
      val result = w3Kit.runCommand[TransferConfirmation](r =>
        ReserveTransfer(txId, targetLotId, r))

      result.reply.error shouldBe defined
      result.reply.error.get.code shouldBe "WFR_010"
    }
  }

  override def afterAll(): Unit = {
    super.afterAll()
  }
}
