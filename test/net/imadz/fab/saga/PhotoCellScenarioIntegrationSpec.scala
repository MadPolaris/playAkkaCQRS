package net.imadz.fab.saga

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import net.imadz.application.aggregates.LotProtocol._
import net.imadz.application.aggregates.WaferProtocol._
import net.imadz.application.aggregates.{LotAggregate, WaferAggregate}
import net.imadz.domain.entities.LotEntity
import net.imadz.domain.entities.LotEntity.{Active => LotActive, LotCreated, LotEvent, LotSealed, LotState, WaferAdditionCommitted, WaferAdditionReserved, WaferRemovalCommitted, WaferRemovalReserved}
import net.imadz.domain.entities.WaferEntity
import net.imadz.domain.entities.WaferEntity.{Active => WaferActive, Scrapped, WaferCreated, WaferEvent, WaferScrapped, WaferTransferCommitted, WaferTransferReserved, WaferState}
import net.imadz.domain.entities.behaviors.{LotEventHandler, WaferEventHandler}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID

/**
 * Integration test: spawns real Lot and Wafer EventSourcedBehavior actors,
 * executes the Split-Rework-Scrap flow manually (simulating Saga TCC steps),
 * and verifies all state transitions end-to-end.
 *
 * This covers the full Photo Cell closed-loop timeline from plan Section 2D table.
 */
class PhotoCellScenarioIntegrationSpec extends ScalaTestWithActorTestKit(FabSagaTestConfig.testConfig)
  with AnyWordSpecLike with BeforeAndAfterAll {

  // Deterministic IDs for reproducibility
  private val sourceLotId = UUID.nameUUIDFromBytes("source-lot".getBytes)
  private val reworkLotId = UUID.nameUUIDFromBytes("rework-lot".getBytes)
  private val w1 = UUID.nameUUIDFromBytes("WAFER-1".getBytes)
  private val w2 = UUID.nameUUIDFromBytes("WAFER-2".getBytes)
  private val w3 = UUID.nameUUIDFromBytes("WAFER-3".getBytes)
  private val w4 = UUID.nameUUIDFromBytes("WAFER-4".getBytes)
  private val w5 = UUID.nameUUIDFromBytes("WAFER-5".getBytes)
  private val allFiveWafers = Set(w1, w2, w3, w4, w5)

  // ===================================================================
  // Scenario A: Happy Path — All 5 PASS, Lot Sealed
  // ===================================================================

  "Photo Cell Scenario A (All PASS)" should {
    "create source lot with 5 wafers and seal" in {
      val lotKit = FabSagaTestConfig.createLotTestKit(sourceLotId)

      // A0: CreateLot
      val created = lotKit.runCommand[LotConfirmation](r =>
        CreateLot("PHOTO-CELL-A", allFiveWafers, r))
      created.reply.error shouldBe None
      created.reply.phase shouldBe Some(LotActive)
      created.events.head shouldBe a[LotCreated]

      // A1-A2: Create 5 wafers
      for (wid <- Seq(w1, w2, w3, w4, w5)) {
        val waferKit = FabSagaTestConfig.createWaferTestKit(wid)
        val r = waferKit.runCommand[WaferConfirmation](reply =>
          CreateWafer(sourceLotId, reply))
        r.reply.error shouldBe None
        r.reply.status shouldBe Some(WaferActive)
        r.reply.lotId shouldBe Some(sourceLotId)
      }

      // A4: Seal lot (all wafers passed metrology, lot is done)
      val sealed_ = lotKit.runCommand[LotConfirmation](r => SealLot(r))
      sealed_.reply.error shouldBe None
      sealed_.events should contain(LotSealed())
      sealed_.state.phase shouldBe LotEntity.Sealed
    }
  }

  // ===================================================================
  // Scenario B: FAIL → Rework → PASS (Split saga, corrective feedback)
  // ===================================================================

  "Photo Cell Scenario B (FAIL → Rework → PASS)" should {

    "execute full Split saga: W3 moves source→rework lot" in {
      val sLotKit = FabSagaTestConfig.createLotTestKit(sourceLotId)
      val rLotKit = FabSagaTestConfig.createLotTestKit(reworkLotId)
      val w3Kit = FabSagaTestConfig.createWaferTestKit(w3)
      val splitTxId = UUID.randomUUID()

      // B0: Create source lot and all wafers
      sLotKit.runCommand[LotConfirmation](r => CreateLot("PHOTO-CELL-B", allFiveWafers, r))
      for (wid <- Seq(w1, w2, w3, w4, w5)) {
        val wk = FabSagaTestConfig.createWaferTestKit(wid)
        wk.runCommand[WaferConfirmation](r => CreateWafer(sourceLotId, r))
      }

      // --- TCC Prepare Phase ---

      // B2: Reserve W3 removal from source lot
      val reserveOut = sLotKit.runCommand[WaferRemovalConfirmation](r =>
        ReserveWaferRemoval(splitTxId, Set(w3), r))
      reserveOut.reply.error shouldBe None
      reserveOut.events should contain(WaferRemovalReserved(splitTxId, Set(w3)))
      // Source still has all 5 wafers (not committed yet)
      sLotKit.getState().waferIds should contain(w3)

      // B3: Reserve W3 addition to rework lot
      val reserveIn = rLotKit.runCommand[WaferAdditionConfirmation](r =>
        ReserveAddWafer(splitTxId, Set(w3), r))
      reserveIn.reply.error shouldBe None
      reserveIn.events should contain(WaferAdditionReserved(splitTxId, Set(w3)))
      // Rework lot doesn't have W3 yet
      rLotKit.getState().waferIds should not contain w3

      // B4: Reserve W3 transfer
      val reserveWf = w3Kit.runCommand[TransferConfirmation](r =>
        ReserveTransfer(splitTxId, reworkLotId, r))
      reserveWf.reply.error shouldBe None
      reserveWf.events should contain(WaferTransferReserved(splitTxId, reworkLotId))
      w3Kit.getState().reservedTransfer shouldBe Some((splitTxId, reworkLotId))

      // --- TCC Commit Phase ---

      // B5: Commit W3 transfer to rework lot
      val commitWf = w3Kit.runCommand[TransferConfirmation](r =>
        CommitTransfer(splitTxId, reworkLotId, r))
      commitWf.reply.error shouldBe None
      commitWf.events should contain(WaferTransferCommitted(splitTxId, reworkLotId))
      w3Kit.getState().lotId shouldBe Some(reworkLotId)
      w3Kit.getState().reservedTransfer shouldBe None

      // B6: Commit removal from source lot
      val commitOut = sLotKit.runCommand[WaferRemovalConfirmation](r =>
        CommitWaferRemoval(splitTxId, r))
      commitOut.reply.error shouldBe None
      commitOut.events should contain(WaferRemovalCommitted(splitTxId))
      sLotKit.getState().waferIds should not contain w3
      sLotKit.getState().waferIds should have size 4 // W1,W2,W4,W5 remain

      // B7: Commit addition to rework lot
      val commitIn = rLotKit.runCommand[WaferAdditionConfirmation](r =>
        CommitAddWafer(splitTxId, r))
      commitIn.reply.error shouldBe None
      commitIn.events should contain(WaferAdditionCommitted(splitTxId))
      rLotKit.getState().waferIds should contain(w3) // W3 now in rework lot
      rLotKit.getState().phase shouldBe LotActive
    }

    "return W3 to source lot after rework passes" in {
      val sLotKit = FabSagaTestConfig.createLotTestKit(sourceLotId)
      val rLotKit = FabSagaTestConfig.createLotTestKit(reworkLotId)
      val w3Kit = FabSagaTestConfig.createWaferTestKit(w3)
      val splitTxId = UUID.randomUUID()
      val returnTxId = UUID.randomUUID()

      // Setup: source lot created with 5 wafers, W3 moved to rework
      // (simplified: create source with 4 wafers, rework with W3)
      sLotKit.runCommand[LotConfirmation](r => CreateLot("PHOTO-CELL-B2", Set(w1,w2,w4,w5), r))
      for (wid <- Seq(w1, w2, w4, w5)) {
        val wk = FabSagaTestConfig.createWaferTestKit(wid)
        wk.runCommand[WaferConfirmation](r => CreateWafer(sourceLotId, r))
      }
      // Create rework lot with W3
      rLotKit.runCommand[LotConfirmation](r => CreateLot("PHOTO-RWK-B", Set(w3), r))
      // W3 starts in rework lot
      val w3k2 = FabSagaTestConfig.createWaferTestKit(w3)
      w3k2.runCommand[WaferConfirmation](r => CreateWafer(reworkLotId, r))

      // Now return W3: rework → source
      // Prepare
      sLotKit.runCommand[WaferAdditionConfirmation](r => ReserveAddWafer(returnTxId, Set(w3), r))
      rLotKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(returnTxId, Set(w3), r))
      w3k2.runCommand[TransferConfirmation](r => ReserveTransfer(returnTxId, sourceLotId, r))

      // Commit
      w3k2.runCommand[TransferConfirmation](r => CommitTransfer(returnTxId, sourceLotId, r))
      rLotKit.runCommand[WaferRemovalConfirmation](r => CommitWaferRemoval(returnTxId, r))
      val commitBack = sLotKit.runCommand[WaferAdditionConfirmation](r => CommitAddWafer(returnTxId, r))

      // Verify: W3 back in source lot
      commitBack.reply.error shouldBe None
      sLotKit.getState().waferIds should contain(w3)
      sLotKit.getState().waferIds should have size 5 // W1,W2,W3,W4,W5 all together
      w3k2.getState().lotId shouldBe Some(sourceLotId)
      rLotKit.getState().waferIds should not contain w3
    }
  }

  // ===================================================================
  // Scenario C: FAIL → Rework → FAIL again → SCRAP (terminal feedback)
  // ===================================================================

  "Photo Cell Scenario C (FAIL → Rework → Scrap)" should {
    "scrap wafer after max rework exceeded" in {
      // C0: Create source lot with 5 wafers, W3 needs rework → split to rework lot
      val sLotKit = FabSagaTestConfig.createLotTestKit(sourceLotId)
      val rLotKit = FabSagaTestConfig.createLotTestKit(reworkLotId)
      val w3Kit = FabSagaTestConfig.createWaferTestKit(w3)

      // Setup: W3 is in rework lot after first split
      rLotKit.runCommand[LotConfirmation](r => CreateLot("RWK-SCRAP", Set(w3), r))
      w3Kit.runCommand[WaferConfirmation](r => CreateWafer(reworkLotId, r))

      // C3-C4: After 2nd FAIL, decide to scrap W3
      val scrapResult = w3Kit.runCommand[WaferConfirmation](r =>
        ScrapWafer("Max rework exceeded (2/2 attempts)", r))

      scrapResult.reply.error shouldBe None
      scrapResult.events should contain(WaferScrapped("Max rework exceeded (2/2 attempts)"))
      scrapResult.state.status shouldBe Scrapped
      w3Kit.getState().status shouldBe Scrapped

      // Verify: scrapped wafer cannot be transferred
      val txId = UUID.randomUUID()
      val rejectTransfer = w3Kit.runCommand[TransferConfirmation](r =>
        ReserveTransfer(txId, sourceLotId, r))
      rejectTransfer.reply.error shouldBe defined
      rejectTransfer.reply.error.get.code shouldBe "WFR_010"
    }

    "reject double scrap" in {
      val w3Kit = FabSagaTestConfig.createWaferTestKit(w3)

      // Create then scrap
      w3Kit.runCommand[WaferConfirmation](r => CreateWafer(reworkLotId, r))
      w3Kit.runCommand[WaferConfirmation](r => ScrapWafer("first scrap", r))

      // Second scrap attempt must fail
      val secondScrap = w3Kit.runCommand[WaferConfirmation](r =>
        ScrapWafer("attempt to re-scrap", r))
      secondScrap.reply.error shouldBe defined
      secondScrap.reply.error.get.code shouldBe "WFR_020"
    }
  }

  // ===================================================================
  // TCC Compensate: Release all reservations on failure
  // ===================================================================

  "TCC Compensate flow" should {
    "release all reservations when saga is cancelled" in {
      val sLotKit = FabSagaTestConfig.createLotTestKit(sourceLotId)
      val rLotKit = FabSagaTestConfig.createLotTestKit(reworkLotId)
      val w3Kit = FabSagaTestConfig.createWaferTestKit(w3)
      val txId = UUID.randomUUID()

      // Setup lots and wafer
      sLotKit.runCommand[LotConfirmation](r => CreateLot("PHOTO-COMP", allFiveWafers, r))
      w3Kit.runCommand[WaferConfirmation](r => CreateWafer(sourceLotId, r))

      // Prepare Phase (all succeed)
      sLotKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(txId, Set(w3), r))
      rLotKit.runCommand[WaferAdditionConfirmation](r => ReserveAddWafer(txId, Set(w3), r))
      w3Kit.runCommand[TransferConfirmation](r => ReserveTransfer(txId, reworkLotId, r))

      // --- Simulate failure: Compensate ---

      // Compensate: Cancel target lot addition
      val cancelAdd = rLotKit.runCommand[WaferAdditionConfirmation](r => CancelAddWafer(txId, r))
      cancelAdd.reply.error shouldBe None
      rLotKit.getState().incomingWafers should not contain key(txId)

      // Compensate: Release source lot reservation
      val releaseSrc = sLotKit.runCommand[WaferRemovalConfirmation](r => ReleaseReservedWafer(txId, r))
      releaseSrc.reply.error shouldBe None
      sLotKit.getState().reservedWafers should not contain key(txId)
      sLotKit.getState().waferIds should contain(w3) // W3 stays in source

      // Compensate: Release wafer transfer
      val releaseWf = w3Kit.runCommand[TransferConfirmation](r => ReleaseTransfer(txId, r))
      releaseWf.reply.error shouldBe None
      w3Kit.getState().reservedTransfer shouldBe None
      w3Kit.getState().lotId shouldBe Some(sourceLotId) // unchanged
    }
  }

  // ===================================================================
  // Idempotency: Split then retry same split
  // ===================================================================

  "Idempotent Saga" should {
    "accept repeat reservation commands without duplicate events" in {
      val sLotKit = FabSagaTestConfig.createLotTestKit(sourceLotId)
      val w3Kit = FabSagaTestConfig.createWaferTestKit(w3)
      val txId = UUID.randomUUID()

      sLotKit.runCommand[LotConfirmation](r => CreateLot("PHOTO-IDEM", allFiveWafers, r))
      w3Kit.runCommand[WaferConfirmation](r => CreateWafer(sourceLotId, r))

      // First reservation
      sLotKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(txId, Set(w3), r))
      w3Kit.runCommand[TransferConfirmation](r => ReserveTransfer(txId, reworkLotId, r))

      // Retry with same transferId (idempotent)
      val retryLot = sLotKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(txId, Set(w3), r))
      retryLot.reply.error shouldBe None
      retryLot.events shouldBe empty // no new events

      val retryWafer = w3Kit.runCommand[TransferConfirmation](r => ReserveTransfer(txId, reworkLotId, r))
      retryWafer.reply.error shouldBe None
      retryWafer.events shouldBe empty // no new events
    }
  }

  override def afterAll(): Unit = {
    super.afterAll()
  }
}
