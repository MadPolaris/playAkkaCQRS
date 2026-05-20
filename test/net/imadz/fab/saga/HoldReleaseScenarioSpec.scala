package net.imadz.fab.saga

import net.imadz.application.services.transactor._

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import net.imadz.application.aggregates.LotProtocol._
import net.imadz.domain.entities.LotEntity.{Active => LotActive, _}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID

class HoldReleaseScenarioSpec extends ScalaTestWithActorTestKit(FabSagaTestConfig.testConfig)
  with AnyWordSpecLike with BeforeAndAfterEach {

  private var sourceLotId: UUID = _
  private var holdLotId: UUID = _
  private var w1: UUID = _
  private var w2: UUID = _
  private var w3: UUID = _
  private var w4: UUID = _
  private var w5: UUID = _

  override def beforeEach(): Unit = {
    sourceLotId = UUID.randomUUID()
    holdLotId = UUID.randomUUID()
    w1 = UUID.randomUUID()
    w2 = UUID.randomUUID()
    w3 = UUID.randomUUID()
    w4 = UUID.randomUUID()
    w5 = UUID.randomUUID()
  }

  private def waferName(uuid: UUID): String = {
    if (uuid == w1) "W1" else if (uuid == w2) "W2"
    else if (uuid == w3) "W3" else if (uuid == w4) "W4"
    else if (uuid == w5) "W5" else "UNKNOWN"
  }

  private def waferNames(uuids: Set[UUID]): Set[String] = uuids.map(waferName)

  "Hold & Release" should {
    "split W1 to hold lot, place on hold, release, merge back" in {
      val sKit = FabSagaTestConfig.createLotTestKit(sourceLotId)
      val hKit = FabSagaTestConfig.createLotTestKit(holdLotId)
      val splitTxId = UUID.randomUUID()
      val mergeTxId = UUID.randomUUID()
      val allFive = Map(w1 -> "W1", w2 -> "W2", w3 -> "W3", w4 -> "W4", w5 -> "W5")

      // Setup
      sKit.runCommand[LotConfirmation](r => CreateLot("HOLD-SRC", allFive, r))
      hKit.runCommand[LotConfirmation](r => CreateLot("HOLD-HLD", Map.empty, r))

      // --- Split: TCC transfer W1 to hold lot (2-participant) ---
      sKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(splitTxId, Set(w1), Set("W1"), r))
      hKit.runCommand[WaferAdditionConfirmation](r => ReserveAddWafer(splitTxId, Set(w1), r))

      sKit.runCommand[WaferRemovalConfirmation](r => CommitWaferRemoval(splitTxId, r))
      hKit.runCommand[WaferAdditionConfirmation](r => CommitAddWafer(splitTxId, r))

      sKit.getState().waferIds should have size 4
      hKit.getState().waferIds should contain(w1)

      // --- Place on hold via Lot event ---
      val holdResult = hKit.runCommand[LotConfirmation](r =>
        RecordWafersHeld(Set("W1"), "borderline CD measurement", r))
      holdResult.reply.error shouldBe None

      // --- Release ---
      val releaseResult = hKit.runCommand[LotConfirmation](r =>
        RecordWafersReleased(Set("W1"), r))
      releaseResult.reply.error shouldBe None

      // --- Merge back ---
      sKit.runCommand[WaferAdditionConfirmation](r => ReserveAddWafer(mergeTxId, Set(w1), r))
      hKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(mergeTxId, Set(w1), Set("W1"), r))

      hKit.runCommand[WaferRemovalConfirmation](r => CommitWaferRemoval(mergeTxId, r))
      sKit.runCommand[WaferAdditionConfirmation](r => CommitAddWafer(mergeTxId, r))

      sKit.getState().waferIds should have size 5
      sKit.getState().waferIds should contain(w1)
      hKit.getState().waferIds shouldBe empty
    }

    "record wafers held and scrapped on lot" in {
      val hKit = FabSagaTestConfig.createLotTestKit(holdLotId)

      hKit.runCommand[LotConfirmation](r => CreateLot("HOLD-FAIL", Map(w1 -> "W1"), r))
      hKit.runCommand[LotConfirmation](r => RecordWafersHeld(Set("W1"), "CD out of spec", r))

      // Record SCRAP classification as replacement for scrap command
      hKit.runCommand[LotConfirmation](r =>
        RecordWaferClassified(w1, "SCRAP", 0, 50.0, r))
      hKit.getState().waferClassifications.get(w1).map(_.classification) shouldBe Some("SCRAP")
    }

    "TCC compensate: cancel addition and release source reservation" in {
      val sKit = FabSagaTestConfig.createLotTestKit(sourceLotId)
      val hKit = FabSagaTestConfig.createLotTestKit(holdLotId)
      val txId = UUID.randomUUID()

      sKit.runCommand[LotConfirmation](r => CreateLot("HOLD-COMP", Map(w1 -> "W1", w2 -> "W2"), r))
      hKit.runCommand[LotConfirmation](r => CreateLot("HOLD-CMP", Map.empty, r))

      sKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(txId, Set(w1), Set("W1"), r))
      hKit.runCommand[WaferAdditionConfirmation](r => ReserveAddWafer(txId, Set(w1), r))

      // Compensate
      val cancelAdd = hKit.runCommand[WaferAdditionConfirmation](r => CancelAddWafer(txId, r))
      cancelAdd.reply.error shouldBe None
      hKit.getState().incomingWafers should not contain key(txId)

      val releaseSrc = sKit.runCommand[WaferRemovalConfirmation](r => ReleaseReservedWafer(txId, r))
      releaseSrc.reply.error shouldBe None
      sKit.getState().waferIds should contain(w1)
    }
  }
}
