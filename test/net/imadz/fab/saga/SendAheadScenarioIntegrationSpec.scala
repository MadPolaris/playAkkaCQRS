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
 * Integration test for Send-Ahead Pilot scenario:
 *   1. Create source lot with 5 wafers
 *   2. Split 1 wafer (W1) → Pilot lot via Saga TCC
 *   3a. Pilot PASS → Merge W1 back to source lot
 *   3b. Pilot FAIL → Scrap W1, source stays at 4 wafers
 *
 * Verifies: Lot genealogy (parent/child), wafer counts, TCC prepare/commit,
 * idempotency.
 */
class SendAheadScenarioIntegrationSpec extends ScalaTestWithActorTestKit(FabSagaTestConfig.testConfig)
  with AnyWordSpecLike with BeforeAndAfterAll {

  private val sourceLotId = UUID.nameUUIDFromBytes("send-ahead-source".getBytes)
  private val pilotLotId = UUID.nameUUIDFromBytes("send-ahead-pilot".getBytes)
  private val w1 = UUID.nameUUIDFromBytes("PILOT-W1".getBytes)
  private val w2 = UUID.nameUUIDFromBytes("PILOT-W2".getBytes)
  private val w3 = UUID.nameUUIDFromBytes("PILOT-W3".getBytes)
  private val w4 = UUID.nameUUIDFromBytes("PILOT-W4".getBytes)
  private val w5 = UUID.nameUUIDFromBytes("PILOT-W5".getBytes)
  private val allFiveWafers = Set(w1, w2, w3, w4, w5)
  private val pilotWafer = Set(w1)

  // ===================================================================
  // Happy Path: Send-Ahead Pilot PASS → Merge
  // ===================================================================

  "Send-Ahead Pilot (PASS → Merge)" should {
    "create source lot with 5 wafers and empty pilot child lot" in {
      val sKit = FabSagaTestConfig.createLotTestKit(sourceLotId)
      val pKit = FabSagaTestConfig.createLotTestKit(pilotLotId)

      // Create source lot
      val src = sKit.runCommand[LotConfirmation](r => CreateLot("SEND-AHEAD-SRC", allFiveWafers, r))
      src.reply.error shouldBe None
      src.state.phase shouldBe LotActive

      // Create pilot lot as empty child
      val plt = pKit.runCommand[LotConfirmation](r => CreateLot("SEND-AHEAD-PILOT", Set.empty, r))
      plt.reply.error shouldBe None
      plt.state.phase shouldBe LotActive
      plt.state.waferIds shouldBe empty
    }

    "split W1 from source to pilot lot via TCC" in {
      val sKit = FabSagaTestConfig.createLotTestKit(sourceLotId)
      val pKit = FabSagaTestConfig.createLotTestKit(pilotLotId)
      val w1Kit = FabSagaTestConfig.createWaferTestKit(w1)
      val txId = UUID.randomUUID()

      // Setup: source with 5 wafers, empty pilot
      sKit.runCommand[LotConfirmation](r => CreateLot("SEND-AHEAD-SRC", allFiveWafers, r))
      pKit.runCommand[LotConfirmation](r => CreateLot("SEND-AHEAD-PILOT", Set.empty, r))
      w1Kit.runCommand[WaferConfirmation](r => CreateWafer(sourceLotId, r))

      // --- Prepare Phase ---
      // Reserve W1 removal from source
      sKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(txId, pilotWafer, r))
      // Reserve W1 addition to pilot
      pKit.runCommand[WaferAdditionConfirmation](r => ReserveAddWafer(txId, pilotWafer, r))
      // Reserve W1 transfer
      w1Kit.runCommand[TransferConfirmation](r => ReserveTransfer(txId, pilotLotId, r))

      // Verify prepare state
      sKit.getState().reservedWafers should contain key txId
      pKit.getState().incomingWafers should contain key txId
      w1Kit.getState().reservedTransfer shouldBe Some((txId, pilotLotId))

      // --- Commit Phase ---
      // Commit W1 transfer
      w1Kit.runCommand[TransferConfirmation](r => CommitTransfer(txId, pilotLotId, r))
      // Commit removal from source
      sKit.runCommand[WaferRemovalConfirmation](r => CommitWaferRemoval(txId, r))
      // Commit addition to pilot
      pKit.runCommand[WaferAdditionConfirmation](r => CommitAddWafer(txId, r))

      // Verify post-commit state
      sKit.getState().waferIds should have size 4
      sKit.getState().waferIds should not contain w1
      pKit.getState().waferIds shouldBe pilotWafer
      pKit.getState().waferIds should contain(w1)
      w1Kit.getState().lotId shouldBe Some(pilotLotId)
      w1Kit.getState().reservedTransfer shouldBe None
    }

    "merge W1 back to source lot after pilot passes" in {
      val sKit = FabSagaTestConfig.createLotTestKit(sourceLotId)
      val pKit = FabSagaTestConfig.createLotTestKit(pilotLotId)
      val w1Kit = FabSagaTestConfig.createWaferTestKit(w1)
      val splitTxId = UUID.randomUUID()
      val mergeTxId = UUID.randomUUID()

      // Setup: source with 4 wafers, pilot with W1
      sKit.runCommand[LotConfirmation](r => CreateLot("SEND-AHEAD-SRC", Set(w2, w3, w4, w5), r))
      for (wid <- Seq(w2, w3, w4, w5)) {
        val wk = FabSagaTestConfig.createWaferTestKit(wid)
        wk.runCommand[WaferConfirmation](r => CreateWafer(sourceLotId, r))
      }
      pKit.runCommand[LotConfirmation](r => CreateLot("SEND-AHEAD-PILOT", pilotWafer, r))
      w1Kit.runCommand[WaferConfirmation](r => CreateWafer(pilotLotId, r))

      // --- Merge (Pilot → Source) ---
      // Prepare
      sKit.runCommand[WaferAdditionConfirmation](r => ReserveAddWafer(mergeTxId, pilotWafer, r))
      pKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(mergeTxId, pilotWafer, r))
      w1Kit.runCommand[TransferConfirmation](r => ReserveTransfer(mergeTxId, sourceLotId, r))

      // Commit
      w1Kit.runCommand[TransferConfirmation](r => CommitTransfer(mergeTxId, sourceLotId, r))
      pKit.runCommand[WaferRemovalConfirmation](r => CommitWaferRemoval(mergeTxId, r))
      sKit.runCommand[WaferAdditionConfirmation](r => CommitAddWafer(mergeTxId, r))

      // Verify: source has all 5 wafers again
      sKit.getState().waferIds should have size 5
      sKit.getState().waferIds should contain(w1)
      pKit.getState().waferIds shouldBe empty
      w1Kit.getState().lotId shouldBe Some(sourceLotId)
    }
  }

  // ===================================================================
  // Failure Path: Send-Ahead Pilot FAIL → Scrap
  // ===================================================================

  "Send-Ahead Pilot (FAIL → Scrap)" should {
    "scrap pilot wafer after fail, source stays at 4 wafers" in {
      val sKit = FabSagaTestConfig.createLotTestKit(sourceLotId)
      val pKit = FabSagaTestConfig.createLotTestKit(pilotLotId)
      val w1Kit = FabSagaTestConfig.createWaferTestKit(w1)

      // Setup: source with 4, pilot with W1
      sKit.runCommand[LotConfirmation](r => CreateLot("SEND-AHEAD-SRC", Set(w2, w3, w4, w5), r))
      for (wid <- Seq(w2, w3, w4, w5)) {
        val wk = FabSagaTestConfig.createWaferTestKit(wid)
        wk.runCommand[WaferConfirmation](r => CreateWafer(sourceLotId, r))
      }
      pKit.runCommand[LotConfirmation](r => CreateLot("SEND-AHEAD-PILOT", pilotWafer, r))
      w1Kit.runCommand[WaferConfirmation](r => CreateWafer(pilotLotId, r))

      // Pilot FAIL → scrap W1
      val scrap = w1Kit.runCommand[WaferConfirmation](r =>
        ScrapWafer("Pilot failed: CD out of spec", r))
      scrap.reply.error shouldBe None
      scrap.state.status shouldBe Scrapped

      // Verify: source still has 4 wafers, no merge happened
      sKit.getState().waferIds should have size 4
      sKit.getState().waferIds should not contain w1
      w1Kit.getState().status shouldBe Scrapped

      // Verify: scrapped wafer cannot be transferred back
      val txId = UUID.randomUUID()
      val reject = w1Kit.runCommand[TransferConfirmation](r =>
        ReserveTransfer(txId, sourceLotId, r))
      reject.reply.error shouldBe defined
      reject.reply.error.get.code shouldBe "WFR_010"
    }
  }

  // ===================================================================
  // Idempotency: Split commands repeated
  // ===================================================================

  "Send-Ahead idempotency" should {
    "accept repeated reserve/commit without duplicate events" in {
      val sKit = FabSagaTestConfig.createLotTestKit(sourceLotId)
      val pKit = FabSagaTestConfig.createLotTestKit(pilotLotId)
      val w1Kit = FabSagaTestConfig.createWaferTestKit(w1)
      val txId = UUID.randomUUID()

      sKit.runCommand[LotConfirmation](r => CreateLot("SEND-AHEAD-IDM", allFiveWafers, r))
      pKit.runCommand[LotConfirmation](r => CreateLot("SEND-AHEAD-PLT", Set.empty, r))
      w1Kit.runCommand[WaferConfirmation](r => CreateWafer(sourceLotId, r))

      // First reserve
      sKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(txId, pilotWafer, r))
      pKit.runCommand[WaferAdditionConfirmation](r => ReserveAddWafer(txId, pilotWafer, r))
      w1Kit.runCommand[TransferConfirmation](r => ReserveTransfer(txId, pilotLotId, r))

      // Repeat reserve (idempotent)
      val retrySrc = sKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(txId, pilotWafer, r))
      retrySrc.reply.error shouldBe None
      retrySrc.events shouldBe empty

      // Commit
      w1Kit.runCommand[TransferConfirmation](r => CommitTransfer(txId, pilotLotId, r))
      sKit.runCommand[WaferRemovalConfirmation](r => CommitWaferRemoval(txId, r))
      pKit.runCommand[WaferAdditionConfirmation](r => CommitAddWafer(txId, r))

      // Repeat commit (idempotent)
      val retryCommit = sKit.runCommand[WaferRemovalConfirmation](r => CommitWaferRemoval(txId, r))
      retryCommit.reply.error shouldBe None
      retryCommit.events shouldBe empty
    }
  }

  override def afterAll(): Unit = {
    super.afterAll()
  }
}
