package net.imadz.fab.saga

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import net.imadz.application.aggregates.LotProtocol._
import net.imadz.application.aggregates.WaferProtocol._
import net.imadz.domain.entities.LotEntity.{Active => LotActive, _}
import net.imadz.domain.entities.WaferEntity.{Active => WaferActive, OnHold, Scrapped, _}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID

class HoldReleaseScenarioSpec extends ScalaTestWithActorTestKit(FabSagaTestConfig.testConfig)
  with AnyWordSpecLike with BeforeAndAfterEach {

  // Random IDs per test to avoid journal leak
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

  // ===================================================================
  // Path A: Hold → Review PASS → Release → Merge
  // ===================================================================

  "Hold & Release (PASS)" should {
    "split W1 to hold lot, place on hold, release, merge back" in {
      val sKit = FabSagaTestConfig.createLotTestKit(sourceLotId)
      val hKit = FabSagaTestConfig.createLotTestKit(holdLotId)
      val w1Kit = FabSagaTestConfig.createWaferTestKit(w1)
      val splitTxId = UUID.randomUUID()
      val mergeTxId = UUID.randomUUID()
      val allFive = Set(w1, w2, w3, w4, w5)

      // Setup
      sKit.runCommand[LotConfirmation](r => CreateLot("HOLD-SRC", allFive, r))
      hKit.runCommand[LotConfirmation](r => CreateLot("HOLD-HLD", Set.empty, r))
      w1Kit.runCommand[WaferConfirmation](r => CreateWafer(sourceLotId, r))

      // --- Split: TCC transfer W1 to hold lot ---
      sKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(splitTxId, Set(w1), r))
      hKit.runCommand[WaferAdditionConfirmation](r => ReserveAddWafer(splitTxId, Set(w1), r))
      val resWf = w1Kit.runCommand[TransferConfirmation](r => ReserveTransfer(splitTxId, holdLotId, r))
      resWf.reply.error shouldBe None

      val cmtWf = w1Kit.runCommand[TransferConfirmation](r => CommitTransfer(splitTxId, holdLotId, r))
      cmtWf.reply.error shouldBe None
      sKit.runCommand[WaferRemovalConfirmation](r => CommitWaferRemoval(splitTxId, r))
      hKit.runCommand[WaferAdditionConfirmation](r => CommitAddWafer(splitTxId, r))

      w1Kit.getState().lotId shouldBe Some(holdLotId)
      sKit.getState().waferIds should have size 4

      // --- Place on hold ---
      val holdResult = w1Kit.runCommand[WaferConfirmation](r =>
        HoldWafer("borderline CD measurement", r))
      holdResult.reply.error shouldBe None
      holdResult.state.status shouldBe OnHold

      // --- Release ---
      val releaseResult = w1Kit.runCommand[WaferConfirmation](r => ReleaseHold(r))
      releaseResult.reply.error shouldBe None
      releaseResult.state.status shouldBe WaferActive

      // --- Merge back ---
      sKit.runCommand[WaferAdditionConfirmation](r => ReserveAddWafer(mergeTxId, Set(w1), r))
      hKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(mergeTxId, Set(w1), r))
      w1Kit.runCommand[TransferConfirmation](r => ReserveTransfer(mergeTxId, sourceLotId, r))

      w1Kit.runCommand[TransferConfirmation](r => CommitTransfer(mergeTxId, sourceLotId, r))
      hKit.runCommand[WaferRemovalConfirmation](r => CommitWaferRemoval(mergeTxId, r))
      sKit.runCommand[WaferAdditionConfirmation](r => CommitAddWafer(mergeTxId, r))

      sKit.getState().waferIds should have size 5
      sKit.getState().waferIds should contain(w1)
      hKit.getState().waferIds shouldBe empty
      w1Kit.getState().lotId shouldBe Some(sourceLotId)
    }
  }

  // ===================================================================
  // Path B: Hold → Review FAIL → Scrap
  // ===================================================================

  "Hold & Release (FAIL → Scrap)" should {
    "scrap held wafer after review fails" in {
      val hKit = FabSagaTestConfig.createLotTestKit(holdLotId)
      val w1Kit = FabSagaTestConfig.createWaferTestKit(w1)

      hKit.runCommand[LotConfirmation](r => CreateLot("HOLD-FAIL", Set(w1), r))
      w1Kit.runCommand[WaferConfirmation](r => CreateWafer(holdLotId, r))
      w1Kit.runCommand[WaferConfirmation](r => HoldWafer("CD out of spec", r))

      val scrapResult = w1Kit.runCommand[WaferConfirmation](r =>
        ScrapWafer("Engineer review: CD beyond recoverable limit", r))

      scrapResult.reply.error shouldBe None
      scrapResult.state.status shouldBe Scrapped
    }

    "reject release on scrapped wafer" in {
      val w2Kit = FabSagaTestConfig.createWaferTestKit(w2)
      w2Kit.runCommand[WaferConfirmation](r => CreateWafer(holdLotId, r))
      w2Kit.runCommand[WaferConfirmation](r => HoldWafer("review", r))
      w2Kit.runCommand[WaferConfirmation](r => ScrapWafer("failed review", r))

      val result = w2Kit.runCommand[WaferConfirmation](r => ReleaseHold(r))
      result.reply.error shouldBe defined
      result.reply.error.get.code shouldBe "WFR_032"
    }
  }

  // ===================================================================
  // Hold invariant checks
  // ===================================================================

  "Hold invariants" should {
    "reject double hold" in {
      val w3Kit = FabSagaTestConfig.createWaferTestKit(w3)
      w3Kit.runCommand[WaferConfirmation](r => CreateWafer(holdLotId, r))
      w3Kit.runCommand[WaferConfirmation](r => HoldWafer("first hold", r))

      val result = w3Kit.runCommand[WaferConfirmation](r =>
        HoldWafer("double hold", r))
      result.reply.error shouldBe defined
      result.reply.error.get.code shouldBe "WFR_030"
    }

    "reject release when not on hold" in {
      val w4Kit = FabSagaTestConfig.createWaferTestKit(w4)
      w4Kit.runCommand[WaferConfirmation](r => CreateWafer(holdLotId, r))

      val result = w4Kit.runCommand[WaferConfirmation](r => ReleaseHold(r))
      result.reply.error shouldBe defined
      result.reply.error.get.code shouldBe "WFR_032"
    }
  }
}
