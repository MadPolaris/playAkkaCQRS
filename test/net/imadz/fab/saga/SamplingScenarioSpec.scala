package net.imadz.fab.saga

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import net.imadz.application.aggregates.LotProtocol._
import net.imadz.domain.entities.LotEntity.{Active => LotActive, _}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID

/**
 * Metrology Sampling scenario: 2-participant TCC (Source Lot + Sample Lot).
 *   Lot(6 wafers) -> Split 2 wafers -> Sample lot -> Measure -> PASS -> Merge
 *   4 wafers Skipped (sample/skip recorded via RecordWafersSampled on Lot)
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
  private val allSixWafers = Map(w1 -> "W1", w2 -> "W2", w3 -> "W3", w4 -> "W4", w5 -> "W5", w6 -> "W6")
  private val sampleWafers = Set(w1, w2)
  private val skipWafers = Map(w3 -> "W3", w4 -> "W4", w5 -> "W5", w6 -> "W6")

  private def name(wafers: Set[UUID]): Set[String] = wafers.map { u =>
    if (u == w1) "W1" else if (u == w2) "W2" else if (u == w3) "W3"
    else if (u == w4) "W4" else if (u == w5) "W5" else if (u == w6) "W6" else "?"
  }

  "Metrology Sampling" should {
    "split 2 wafers to sample lot via 2-participant TCC" in {
      val sKit = FabSagaTestConfig.createLotTestKit(sourceLotId)
      val smpKit = FabSagaTestConfig.createLotTestKit(sampleLotId)
      val txId = UUID.randomUUID()

      // Setup: source with 6 wafers, empty sample lot
      sKit.runCommand[LotConfirmation](r => CreateLot("SAMPLING-SRC", allSixWafers, r))
      smpKit.runCommand[LotConfirmation](r => CreateLot("SAMPLING-SMP", Map.empty, r))

      // --- Prepare Phase ---
      sKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(txId, sampleWafers, name(sampleWafers), r))
      smpKit.runCommand[WaferAdditionConfirmation](r => ReserveAddWafer(txId, sampleWafers, r))

      // --- Commit Phase ---
      sKit.runCommand[WaferRemovalConfirmation](r => CommitWaferRemoval(txId, r))
      smpKit.runCommand[WaferAdditionConfirmation](r => CommitAddWafer(txId, r))

      // Verify: source has 4 remaining, sample lot has 2
      sKit.getState().waferIds should have size 4
      smpKit.getState().waferIds should have size 2
      smpKit.getState().waferIds should contain allOf (w1, w2)
    }

    "record skipped wafers on lot via RecordWafersSampled" in {
      val sKit = FabSagaTestConfig.createLotTestKit(sourceLotId)

      sKit.runCommand[LotConfirmation](r => CreateLot("SAMPLING-SRC2", allSixWafers, r))

      // Record sampling: W1,W2 sampled, W3-W6 skipped
      val sampleIds = Set(w1.toString, w2.toString)
      val skipIds = Set(w3.toString, w4.toString, w5.toString, w6.toString)
      val result = sKit.runCommand[LotConfirmation](r =>
        RecordWafersSampled(sampleIds, skipIds, r))

      result.reply.error shouldBe None
    }

    "merge sample wafers back after measurement passes" in {
      val sKit = FabSagaTestConfig.createLotTestKit(sourceLotId)
      val smpKit = FabSagaTestConfig.createLotTestKit(sampleLotId)
      val mergeTxId = UUID.randomUUID()

      // Setup: source with 4 wafers (skipped), sample with 2 wafers
      sKit.runCommand[LotConfirmation](r => CreateLot("SAMPLING-SRC", skipWafers, r))
      smpKit.runCommand[LotConfirmation](r => CreateLot("SAMPLING-SMP", Map(w1 -> "W1", w2 -> "W2"), r))

      // --- Prepare Phase (Merge) ---
      sKit.runCommand[WaferAdditionConfirmation](r => ReserveAddWafer(mergeTxId, sampleWafers, r))
      smpKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(mergeTxId, sampleWafers, name(sampleWafers), r))

      // --- Commit Phase ---
      smpKit.runCommand[WaferRemovalConfirmation](r => CommitWaferRemoval(mergeTxId, r))
      sKit.runCommand[WaferAdditionConfirmation](r => CommitAddWafer(mergeTxId, r))

      // Verify: source has all 6 wafers again
      sKit.getState().waferIds should have size 6
      sKit.getState().waferIds should contain allOf (w1, w2, w3, w4, w5, w6)
      smpKit.getState().waferIds shouldBe empty
    }
  }

  override def afterAll(): Unit = {
    super.afterAll()
  }
}
