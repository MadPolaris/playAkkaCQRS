package net.imadz.fab.saga

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

  "Hold & Release" should {
    "split W1 to hold lot, place on hold, release, merge back" in {
      val sKit = FabSagaTestConfig.createLotTestKit(sourceLotId)
      val hKit = FabSagaTestConfig.createLotTestKit(holdLotId)
      val splitTxId = UUID.randomUUID()
      val mergeTxId = UUID.randomUUID()
      val allFive = Set(w1, w2, w3, w4, w5)

      // Setup
      sKit.runCommand[LotConfirmation](r => CreateLot("HOLD-SRC", allFive, r))
      hKit.runCommand[LotConfirmation](r => CreateLot("HOLD-HLD", Set.empty, r))

      // --- Split: TCC transfer W1 to hold lot (2-participant) ---
      sKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(splitTxId, Set(w1), r))
      hKit.runCommand[WaferAdditionConfirmation](r => ReserveAddWafer(splitTxId, Set(w1), r))

      sKit.runCommand[WaferRemovalConfirmation](r => CommitWaferRemoval(splitTxId, r))
      hKit.runCommand[WaferAdditionConfirmation](r => CommitAddWafer(splitTxId, r))

      sKit.getState().waferIds should have size 4
      hKit.getState().waferIds should contain(w1)

      // --- Place on hold via Lot event ---
      val holdResult = hKit.runCommand[LotConfirmation](r =>
        RecordWafersHeld(Set(w1.toString), "borderline CD measurement", r))
      holdResult.reply.error shouldBe None

      // --- Release ---
      val releaseResult = hKit.runCommand[LotConfirmation](r =>
        RecordWafersReleased(Set(w1.toString), r))
      releaseResult.reply.error shouldBe None

      // --- Merge back ---
      sKit.runCommand[WaferAdditionConfirmation](r => ReserveAddWafer(mergeTxId, Set(w1), r))
      hKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(mergeTxId, Set(w1), r))

      hKit.runCommand[WaferRemovalConfirmation](r => CommitWaferRemoval(mergeTxId, r))
      sKit.runCommand[WaferAdditionConfirmation](r => CommitAddWafer(mergeTxId, r))

      sKit.getState().waferIds should have size 5
      sKit.getState().waferIds should contain(w1)
      hKit.getState().waferIds shouldBe empty
    }

    "record wafers held and scrapped on lot" in {
      val hKit = FabSagaTestConfig.createLotTestKit(holdLotId)

      hKit.runCommand[LotConfirmation](r => CreateLot("HOLD-FAIL", Set(w1), r))
      hKit.runCommand[LotConfirmation](r => RecordWafersHeld(Set(w1.toString), "CD out of spec", r))

      // Record SCRAP classification as replacement for scrap command
      hKit.runCommand[LotConfirmation](r =>
        RecordWaferClassified(w1.toString, "SCRAP", 0, 50.0, r))
      hKit.getState().waferClassifications.get(w1.toString).map(_.classification) shouldBe Some("SCRAP")
    }

    "TCC compensate: cancel addition and release source reservation" in {
      val sKit = FabSagaTestConfig.createLotTestKit(sourceLotId)
      val hKit = FabSagaTestConfig.createLotTestKit(holdLotId)
      val txId = UUID.randomUUID()

      sKit.runCommand[LotConfirmation](r => CreateLot("HOLD-COMP", Set(w1, w2), r))
      hKit.runCommand[LotConfirmation](r => CreateLot("HOLD-CMP", Set.empty, r))

      sKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(txId, Set(w1), r))
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
