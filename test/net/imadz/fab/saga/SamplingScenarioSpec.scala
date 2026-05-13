package net.imadz.fab.saga

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import net.imadz.application.aggregates.LotProtocol._
import net.imadz.application.aggregates.WaferProtocol._
import net.imadz.domain.entities.LotEntity.{Active => LotActive, _}
import net.imadz.domain.entities.WaferEntity.{Active => WaferActive, Skipped, _}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID

/**
 * Integration test for Metrology Sampling scenario:
 *   Lot(6 wafers) → Split 2 wafers → Sample lot → Measure → PASS → Merge
 *   4 wafers Skipped (don't go through measurement)
 */
class SamplingScenarioSpec extends ScalaTestWithActorTestKit(FabSagaTestConfig.testConfig)
  with AnyWordSpecLike with BeforeAndAfterAll {

  private val sourceLotId = UUID.nameUUIDFromBytes("sampling-source".getBytes)
  private val sampleLotId = UUID.nameUUIDFromBytes("sampling-sample".getBytes)
  private val w1 = UUID.nameUUIDFromBytes("SMP-W1".getBytes)
  private val w2 = UUID.nameUUIDFromBytes("SMP-W2".getBytes)
  private val w3 = UUID.nameUUIDFromBytes("SMP-W3".getBytes)
  private val w4 = UUID.nameUUIDFromBytes("SMP-W4".getBytes)
  private val w5 = UUID.nameUUIDFromBytes("SMP-W5".getBytes)
  private val w6 = UUID.nameUUIDFromBytes("SMP-W6".getBytes)
  private val allSixWafers = Set(w1, w2, w3, w4, w5, w6)
  private val sampleWafers = Set(w1, w2)
  private val skipWafers = Set(w3, w4, w5, w6)

  // ===================================================================
  // Happy Path: Split sample → Measure → Merge
  // ===================================================================

  "Metrology Sampling" should {
    "split 2 wafers to sample lot via TCC" in {
      val sKit = FabSagaTestConfig.createLotTestKit(sourceLotId)
      val smpKit = FabSagaTestConfig.createLotTestKit(sampleLotId)
      val w1Kit = FabSagaTestConfig.createWaferTestKit(w1)
      val w2Kit = FabSagaTestConfig.createWaferTestKit(w2)
      val txId = UUID.randomUUID()

      // Setup: source with 6 wafers, empty sample lot
      sKit.runCommand[LotConfirmation](r => CreateLot("SAMPLING-SRC", allSixWafers, r))
      smpKit.runCommand[LotConfirmation](r => CreateLot("SAMPLING-SMP", Set.empty, r))

      // Create sample wafers
      for (wid <- sampleWafers) {
        val wk = FabSagaTestConfig.createWaferTestKit(wid)
        wk.runCommand[WaferConfirmation](r => CreateWafer(sourceLotId, r))
      }

      // --- Prepare Phase ---
      sKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(txId, sampleWafers, r))
      smpKit.runCommand[WaferAdditionConfirmation](r => ReserveAddWafer(txId, sampleWafers, r))
      for (wid <- sampleWafers) {
        val wk = FabSagaTestConfig.createWaferTestKit(wid)
        wk.runCommand[TransferConfirmation](r => ReserveTransfer(txId, sampleLotId, r))
      }

      // --- Commit Phase ---
      for (wid <- sampleWafers) {
        val wk = FabSagaTestConfig.createWaferTestKit(wid)
        wk.runCommand[TransferConfirmation](r => CommitTransfer(txId, sampleLotId, r))
      }
      sKit.runCommand[WaferRemovalConfirmation](r => CommitWaferRemoval(txId, r))
      smpKit.runCommand[WaferAdditionConfirmation](r => CommitAddWafer(txId, r))

      // Verify: source has 4 remaining, sample lot has 2
      sKit.getState().waferIds should have size 4
      smpKit.getState().waferIds should have size 2
      smpKit.getState().waferIds should contain allOf (w1, w2)
    }

    "skip remaining 4 wafers from measurement" in {
      for (wid <- skipWafers) {
        val wk = FabSagaTestConfig.createWaferTestKit(wid)
        wk.runCommand[WaferConfirmation](r => CreateWafer(sourceLotId, r))

        val result = wk.runCommand[WaferConfirmation](r =>
          SkipWafer("sampling: metrology skipped", r))

        result.reply.error shouldBe None
        result.events should contain(WaferSkipped("sampling: metrology skipped"))
        result.state.status shouldBe Skipped
      }
    }

    "merge sample wafers back after measurement passes" in {
      val sKit = FabSagaTestConfig.createLotTestKit(sourceLotId)
      val smpKit = FabSagaTestConfig.createLotTestKit(sampleLotId)
      val mergeTxId = UUID.randomUUID()

      // Setup: source with 4 wafers (skipped), sample with 2 wafers
      sKit.runCommand[LotConfirmation](r => CreateLot("SAMPLING-SRC", skipWafers, r))
      for (wid <- skipWafers) {
        val wk = FabSagaTestConfig.createWaferTestKit(wid)
        wk.runCommand[WaferConfirmation](r => CreateWafer(sourceLotId, r))
      }
      smpKit.runCommand[LotConfirmation](r => CreateLot("SAMPLING-SMP", sampleWafers, r))
      for (wid <- sampleWafers) {
        val wk = FabSagaTestConfig.createWaferTestKit(wid)
        wk.runCommand[WaferConfirmation](r => CreateWafer(sampleLotId, r))
      }

      // --- Prepare Phase (Merge) ---
      sKit.runCommand[WaferAdditionConfirmation](r => ReserveAddWafer(mergeTxId, sampleWafers, r))
      smpKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(mergeTxId, sampleWafers, r))
      for (wid <- sampleWafers) {
        val wk = FabSagaTestConfig.createWaferTestKit(wid)
        wk.runCommand[TransferConfirmation](r => ReserveTransfer(mergeTxId, sourceLotId, r))
      }

      // --- Commit Phase ---
      for (wid <- sampleWafers) {
        val wk = FabSagaTestConfig.createWaferTestKit(wid)
        wk.runCommand[TransferConfirmation](r => CommitTransfer(mergeTxId, sourceLotId, r))
      }
      smpKit.runCommand[WaferRemovalConfirmation](r => CommitWaferRemoval(mergeTxId, r))
      sKit.runCommand[WaferAdditionConfirmation](r => CommitAddWafer(mergeTxId, r))

      // Verify: source has all 6 wafers again
      sKit.getState().waferIds should have size 6
      sKit.getState().waferIds should contain allOf (w1, w2, w3, w4, w5, w6)
      smpKit.getState().waferIds shouldBe empty
    }

    "reject skip on already skipped wafer" in {
      val w3Kit = FabSagaTestConfig.createWaferTestKit(w3)
      w3Kit.runCommand[WaferConfirmation](r => CreateWafer(sourceLotId, r))
      w3Kit.runCommand[WaferConfirmation](r => SkipWafer("first skip", r))

      val result = w3Kit.runCommand[WaferConfirmation](r =>
        SkipWafer("double skip attempt", r))

      result.reply.error shouldBe defined
      result.reply.error.get.code shouldBe "WFR_040"
    }

    "reject skip on scrapped wafer" in {
      val w4Kit = FabSagaTestConfig.createWaferTestKit(w4)
      w4Kit.runCommand[WaferConfirmation](r => CreateWafer(sourceLotId, r))
      w4Kit.runCommand[WaferConfirmation](r => ScrapWafer("defect", r))

      val result = w4Kit.runCommand[WaferConfirmation](r =>
        SkipWafer("skip scrapped", r))

      result.reply.error shouldBe defined
      result.reply.error.get.code shouldBe "WFR_041"
    }
  }

  override def afterAll(): Unit = {
    super.afterAll()
  }
}
