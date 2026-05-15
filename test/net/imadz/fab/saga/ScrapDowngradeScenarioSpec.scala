package net.imadz.fab.saga

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import net.imadz.application.aggregates.LotProtocol._
import net.imadz.domain.entities.LotEntity.{Active => LotActive, _}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID

/**
 * Scrap & Downgrade scenario: scrap is recorded as Lot event (RecordWaferClassified SCRAP)
 * without creating a child lot.
 */
class ScrapDowngradeScenarioSpec extends ScalaTestWithActorTestKit(FabSagaTestConfig.testConfig)
  with AnyWordSpecLike with BeforeAndAfterAll {

  private val sourceLotId = UUID.nameUUIDFromBytes("scrap-source".getBytes)
  private val w1 = UUID.nameUUIDFromBytes("SCRAP-W1".getBytes)
  private val w2 = UUID.nameUUIDFromBytes("SCRAP-W2".getBytes)
  private val w3 = UUID.nameUUIDFromBytes("SCRAP-W3".getBytes)
  private val threeWafers = Set(w1, w2, w3)

  "Scrap & Downgrade" should {
    "record scrapped wafer classification on lot" in {
      val sKit = FabSagaTestConfig.createLotTestKit(sourceLotId)

      sKit.runCommand[LotConfirmation](r => CreateLot("SCRAP-PROD", threeWafers, r))
      sKit.getState().waferIds should have size 3
      sKit.getState().phase shouldBe LotActive

      // Record SCRAP classification
      val scrapResult = sKit.runCommand[LotConfirmation](r =>
        RecordWaferClassified("SCRAP-W1", "SCRAP", 0, 32.0, r))
      scrapResult.reply.error shouldBe None
      scrapResult.state.waferClassifications should contain key "SCRAP-W1"
      scrapResult.state.waferClassifications("SCRAP-W1").classification shouldBe "SCRAP"
    }

    "classify multiple wafers and track them independently" in {
      val sKit = FabSagaTestConfig.createLotTestKit(sourceLotId)

      sKit.runCommand[LotConfirmation](r => CreateLot("SCRAP-PROD2", threeWafers, r))

      sKit.runCommand[LotConfirmation](r => RecordWaferClassified("SCRAP-W1", "PASS", 0, 30.0, r))
      sKit.runCommand[LotConfirmation](r => RecordWaferClassified("SCRAP-W2", "FAIL", 0, 36.0, r))
      sKit.runCommand[LotConfirmation](r => RecordWaferClassified("SCRAP-W3", "SCRAP", 0, 40.0, r))

      val state = sKit.getState()
      state.waferClassifications("SCRAP-W1").classification shouldBe "PASS"
      state.waferClassifications("SCRAP-W2").classification shouldBe "FAIL"
      state.waferClassifications("SCRAP-W3").classification shouldBe "SCRAP"
    }

    "idempotent: repeat classification yields same result" in {
      val sKit = FabSagaTestConfig.createLotTestKit(sourceLotId)

      sKit.runCommand[LotConfirmation](r => CreateLot("SCRAP-IDM", threeWafers, r))
      sKit.runCommand[LotConfirmation](r => RecordWaferClassified("SCRAP-W1", "SCRAP", 0, 35.0, r))

      val retry = sKit.runCommand[LotConfirmation](r =>
        RecordWaferClassified("SCRAP-W1", "SCRAP", 0, 35.0, r))
      retry.reply.error shouldBe None
      retry.events shouldBe empty
    }
  }

  override def afterAll(): Unit = {
    super.afterAll()
  }
}
