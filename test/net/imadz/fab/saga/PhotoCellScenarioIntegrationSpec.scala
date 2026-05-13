package net.imadz.fab.saga

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import net.imadz.application.aggregates.LotProtocol._
import net.imadz.application.aggregates.WaferProtocol._
import net.imadz.domain.entities.LotEntity.{Active => LotActive, _}
import net.imadz.domain.entities.WaferEntity.{Active => WaferActive, Scrapped, _}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID

class PhotoCellScenarioIntegrationSpec extends ScalaTestWithActorTestKit(FabSagaTestConfig.testConfig)
  with AnyWordSpecLike with BeforeAndAfterEach {

  // Random IDs per test to avoid journal leak across tests
  private var sourceLotId: UUID = _
  private var reworkLotId: UUID = _
  private var w1: UUID = _
  private var w2: UUID = _
  private var w3: UUID = _
  private var w4: UUID = _
  private var w5: UUID = _

  override def beforeEach(): Unit = {
    sourceLotId = UUID.randomUUID()
    reworkLotId = UUID.randomUUID()
    w1 = UUID.randomUUID()
    w2 = UUID.randomUUID()
    w3 = UUID.randomUUID()
    w4 = UUID.randomUUID()
    w5 = UUID.randomUUID()
  }

  private def allFiveWafers: Set[UUID] = Set(w1, w2, w3, w4, w5)

  // ===================================================================
  // Scenario A: Happy Path — All 5 PASS, Lot Sealed
  // ===================================================================

  "Photo Cell Scenario A (All PASS)" should {
    "create source lot with 5 wafers and seal" in {
      val lotKit = FabSagaTestConfig.createLotTestKit(sourceLotId)

      val created = lotKit.runCommand[LotConfirmation](r =>
        CreateLot("PHOTO-CELL-A", allFiveWafers, r))
      created.reply.error shouldBe None
      created.reply.phase shouldBe Some(LotActive)
      created.events.head shouldBe a[LotCreated]

      for (wid <- Seq(w1, w2, w3, w4, w5)) {
        val waferKit = FabSagaTestConfig.createWaferTestKit(wid)
        val r = waferKit.runCommand[WaferConfirmation](reply =>
          CreateWafer(sourceLotId, reply))
        r.reply.error shouldBe None
        r.reply.status shouldBe Some(WaferActive)
        r.reply.lotId shouldBe Some(sourceLotId)
      }

      val sealed_ = lotKit.runCommand[LotConfirmation](r => SealLot(r))
      sealed_.reply.error shouldBe None
      sealed_.events should contain(LotSealed())
      sealed_.state.phase shouldBe Sealed
    }
  }

  // ===================================================================
  // Scenario B: FAIL → Rework → PASS
  // ===================================================================

  "Photo Cell Scenario B (FAIL → Rework → PASS)" should {

    "execute full Split saga: W3 moves source→rework lot" in {
      val sLotKit = FabSagaTestConfig.createLotTestKit(sourceLotId)
      val rLotKit = FabSagaTestConfig.createLotTestKit(reworkLotId)
      val w3Kit = FabSagaTestConfig.createWaferTestKit(w3)
      val splitTxId = UUID.randomUUID()

      // B0: Create source lot, rework lot (empty), and all wafers
      sLotKit.runCommand[LotConfirmation](r => CreateLot("PHOTO-CELL-B", allFiveWafers, r))
      rLotKit.runCommand[LotConfirmation](r => CreateLot("PHOTO-RWK", Set.empty, r))
      // Create all wafer aggregates (reuse w3Kit)
      w3Kit.runCommand[WaferConfirmation](r => CreateWafer(sourceLotId, r))
      for (wid <- Seq(w1, w2, w4, w5)) {
        val wk = FabSagaTestConfig.createWaferTestKit(wid)
        wk.runCommand[WaferConfirmation](r => CreateWafer(sourceLotId, r))
      }

      // --- TCC Prepare Phase ---
      val reserveOut = sLotKit.runCommand[WaferRemovalConfirmation](r =>
        ReserveWaferRemoval(splitTxId, Set(w3), r))
      reserveOut.reply.error shouldBe None
      sLotKit.getState().waferIds should contain(w3)

      val reserveIn = rLotKit.runCommand[WaferAdditionConfirmation](r =>
        ReserveAddWafer(splitTxId, Set(w3), r))
      reserveIn.reply.error shouldBe None
      rLotKit.getState().waferIds should not contain w3

      val reserveWf = w3Kit.runCommand[TransferConfirmation](r =>
        ReserveTransfer(splitTxId, reworkLotId, r))
      reserveWf.reply.error shouldBe None
      w3Kit.getState().reservedTransfer shouldBe Some((splitTxId, reworkLotId))

      // --- TCC Commit Phase ---
      val commitWf = w3Kit.runCommand[TransferConfirmation](r =>
        CommitTransfer(splitTxId, reworkLotId, r))
      commitWf.reply.error shouldBe None
      w3Kit.getState().lotId shouldBe Some(reworkLotId)

      val commitOut = sLotKit.runCommand[WaferRemovalConfirmation](r =>
        CommitWaferRemoval(splitTxId, r))
      commitOut.reply.error shouldBe None
      sLotKit.getState().waferIds should not contain w3
      sLotKit.getState().waferIds should have size 4

      val commitIn = rLotKit.runCommand[WaferAdditionConfirmation](r =>
        CommitAddWafer(splitTxId, r))
      commitIn.reply.error shouldBe None
      rLotKit.getState().waferIds should contain(w3)
      rLotKit.getState().phase shouldBe LotActive
    }

    "return W3 to source lot after rework passes" in {
      val sLotKit = FabSagaTestConfig.createLotTestKit(sourceLotId)
      val rLotKit = FabSagaTestConfig.createLotTestKit(reworkLotId)
      val w3Kit = FabSagaTestConfig.createWaferTestKit(w3)
      val returnTxId = UUID.randomUUID()

      // Setup: source with 4 wafers, rework with W3
      sLotKit.runCommand[LotConfirmation](r => CreateLot("PHOTO-CELL-B2", Set(w1, w2, w4, w5), r))
      for (wid <- Seq(w1, w2, w4, w5)) {
        val wk = FabSagaTestConfig.createWaferTestKit(wid)
        wk.runCommand[WaferConfirmation](r => CreateWafer(sourceLotId, r))
      }
      rLotKit.runCommand[LotConfirmation](r => CreateLot("PHOTO-RWK-B", Set(w3), r))
      val w3k2 = FabSagaTestConfig.createWaferTestKit(w3)
      w3k2.runCommand[WaferConfirmation](r => CreateWafer(reworkLotId, r))

      // Return W3: rework → source
      sLotKit.runCommand[WaferAdditionConfirmation](r => ReserveAddWafer(returnTxId, Set(w3), r))
      rLotKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(returnTxId, Set(w3), r))
      w3k2.runCommand[TransferConfirmation](r => ReserveTransfer(returnTxId, sourceLotId, r))

      w3k2.runCommand[TransferConfirmation](r => CommitTransfer(returnTxId, sourceLotId, r))
      rLotKit.runCommand[WaferRemovalConfirmation](r => CommitWaferRemoval(returnTxId, r))
      val commitBack = sLotKit.runCommand[WaferAdditionConfirmation](r => CommitAddWafer(returnTxId, r))

      commitBack.reply.error shouldBe None
      sLotKit.getState().waferIds should contain(w3)
      sLotKit.getState().waferIds should have size 5
      w3k2.getState().lotId shouldBe Some(sourceLotId)
      rLotKit.getState().waferIds should not contain w3
    }
  }

  // ===================================================================
  // Scenario C: FAIL → Rework → FAIL again → SCRAP
  // ===================================================================

  "Photo Cell Scenario C (FAIL → Rework → Scrap)" should {
    "scrap wafer after max rework exceeded" in {
      val scrapLotId = UUID.randomUUID()
      val w3Kit = FabSagaTestConfig.createWaferTestKit(w3)

      val rLotKit2 = FabSagaTestConfig.createLotTestKit(scrapLotId)
      rLotKit2.runCommand[LotConfirmation](r => CreateLot("RWK-SCRAP", Set(w3), r))
      w3Kit.runCommand[WaferConfirmation](r => CreateWafer(scrapLotId, r))

      val scrapResult = w3Kit.runCommand[WaferConfirmation](r =>
        ScrapWafer("Max rework exceeded (2/2 attempts)", r))

      scrapResult.reply.error shouldBe None
      scrapResult.events should contain(WaferScrapped("Max rework exceeded (2/2 attempts)"))
      scrapResult.state.status shouldBe Scrapped

      val txId = UUID.randomUUID()
      val rejectTransfer = w3Kit.runCommand[TransferConfirmation](r =>
        ReserveTransfer(txId, sourceLotId, r))
      rejectTransfer.reply.error shouldBe defined
      rejectTransfer.reply.error.get.code shouldBe "WFR_010"
    }

    "reject double scrap" in {
      val w3Kit = FabSagaTestConfig.createWaferTestKit(w3)

      w3Kit.runCommand[WaferConfirmation](r => CreateWafer(reworkLotId, r))
      w3Kit.runCommand[WaferConfirmation](r => ScrapWafer("first scrap", r))

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

      sLotKit.runCommand[LotConfirmation](r => CreateLot("PHOTO-COMP", allFiveWafers, r))
      rLotKit.runCommand[LotConfirmation](r => CreateLot("PHOTO-RWK-COMP", Set.empty, r))
      w3Kit.runCommand[WaferConfirmation](r => CreateWafer(sourceLotId, r))

      // Prepare Phase
      sLotKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(txId, Set(w3), r))
      rLotKit.runCommand[WaferAdditionConfirmation](r => ReserveAddWafer(txId, Set(w3), r))
      w3Kit.runCommand[TransferConfirmation](r => ReserveTransfer(txId, reworkLotId, r))

      // Compensate
      val cancelAdd = rLotKit.runCommand[WaferAdditionConfirmation](r => CancelAddWafer(txId, r))
      cancelAdd.reply.error shouldBe None
      rLotKit.getState().incomingWafers should not contain key(txId)

      val releaseSrc = sLotKit.runCommand[WaferRemovalConfirmation](r => ReleaseReservedWafer(txId, r))
      releaseSrc.reply.error shouldBe None
      sLotKit.getState().reservedWafers should not contain key(txId)
      sLotKit.getState().waferIds should contain(w3)

      val releaseWf = w3Kit.runCommand[TransferConfirmation](r => ReleaseTransfer(txId, r))
      releaseWf.reply.error shouldBe None
      w3Kit.getState().reservedTransfer shouldBe None
      w3Kit.getState().lotId shouldBe Some(sourceLotId)
    }
  }

  // ===================================================================
  // Idempotency
  // ===================================================================

  "Idempotent Saga" should {
    "accept repeat reservation commands without duplicate events" in {
      val sLotKit = FabSagaTestConfig.createLotTestKit(sourceLotId)
      val w3Kit = FabSagaTestConfig.createWaferTestKit(w3)
      val txId = UUID.randomUUID()

      sLotKit.runCommand[LotConfirmation](r => CreateLot("PHOTO-IDEM", allFiveWafers, r))
      w3Kit.runCommand[WaferConfirmation](r => CreateWafer(sourceLotId, r))

      sLotKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(txId, Set(w3), r))
      w3Kit.runCommand[TransferConfirmation](r => ReserveTransfer(txId, reworkLotId, r))

      val retryLot = sLotKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(txId, Set(w3), r))
      retryLot.reply.error shouldBe None
      retryLot.events shouldBe empty

      val retryWafer = w3Kit.runCommand[TransferConfirmation](r => ReserveTransfer(txId, reworkLotId, r))
      retryWafer.reply.error shouldBe None
      retryWafer.events shouldBe empty
    }
  }
}
