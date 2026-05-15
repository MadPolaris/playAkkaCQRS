package net.imadz.fab.saga

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import net.imadz.application.aggregates.LotProtocol._
import net.imadz.domain.entities.LotEntity.{Active => LotActive, _}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID

class PhotoCellScenarioIntegrationSpec extends ScalaTestWithActorTestKit(FabSagaTestConfig.testConfig)
  with AnyWordSpecLike with BeforeAndAfterEach {

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

  "Photo Cell Scenario A (All PASS)" should {
    "create source lot with 5 wafers and seal" in {
      val lotKit = FabSagaTestConfig.createLotTestKit(sourceLotId)

      val created = lotKit.runCommand[LotConfirmation](r =>
        CreateLot("PHOTO-CELL-A", allFiveWafers, r))
      created.reply.error shouldBe None
      created.reply.phase shouldBe Some(LotActive)
      created.events.head shouldBe a[LotCreated]
      created.state.waferIds should have size 5

      // Classify all as PASS
      for (wid <- allFiveWafers) {
        lotKit.runCommand[LotConfirmation](r =>
          RecordWaferClassified(wid.toString, "PASS", 0, 30.0, r))
      }

      val sealed_ = lotKit.runCommand[LotConfirmation](r => SealLot(r))
      sealed_.reply.error shouldBe None
      sealed_.events should contain(LotSealed())
      sealed_.state.phase shouldBe Sealed
    }
  }

  "Photo Cell Scenario B (FAIL -> Rework -> PASS)" should {
    "execute full Split: W3 moves source->rework lot via 2-participant TCC" in {
      val sLotKit = FabSagaTestConfig.createLotTestKit(sourceLotId)
      val rLotKit = FabSagaTestConfig.createLotTestKit(reworkLotId)
      val splitTxId = UUID.randomUUID()

      sLotKit.runCommand[LotConfirmation](r => CreateLot("PHOTO-CELL-B", allFiveWafers, r))
      rLotKit.runCommand[LotConfirmation](r => CreateLot("PHOTO-RWK", Set.empty, r))

      // --- TCC Prepare Phase ---
      val reserveOut = sLotKit.runCommand[WaferRemovalConfirmation](r =>
        ReserveWaferRemoval(splitTxId, Set(w3), r))
      reserveOut.reply.error shouldBe None
      sLotKit.getState().waferIds should contain(w3)

      val reserveIn = rLotKit.runCommand[WaferAdditionConfirmation](r =>
        ReserveAddWafer(splitTxId, Set(w3), r))
      reserveIn.reply.error shouldBe None

      // --- TCC Commit Phase ---
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
      val returnTxId = UUID.randomUUID()

      // Setup: source with 4 wafers, rework with W3
      sLotKit.runCommand[LotConfirmation](r => CreateLot("PHOTO-CELL-B2", Set(w1, w2, w4, w5), r))
      rLotKit.runCommand[LotConfirmation](r => CreateLot("PHOTO-RWK-B", Set(w3), r))

      // Return W3: rework -> source
      sLotKit.runCommand[WaferAdditionConfirmation](r => ReserveAddWafer(returnTxId, Set(w3), r))
      rLotKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(returnTxId, Set(w3), r))

      rLotKit.runCommand[WaferRemovalConfirmation](r => CommitWaferRemoval(returnTxId, r))
      val commitBack = sLotKit.runCommand[WaferAdditionConfirmation](r => CommitAddWafer(returnTxId, r))

      commitBack.reply.error shouldBe None
      sLotKit.getState().waferIds should contain(w3)
      sLotKit.getState().waferIds should have size 5
      rLotKit.getState().waferIds should not contain w3
    }
  }

  "Photo Cell Scenario C (FAIL -> Rework -> Scrap)" should {
    "record SCRAP classification on rework lot" in {
      val scrapLotId = UUID.randomUUID()
      val rLotKit = FabSagaTestConfig.createLotTestKit(scrapLotId)

      rLotKit.runCommand[LotConfirmation](r => CreateLot("RWK-SCRAP", Set(w3), r))

      val scrapResult = rLotKit.runCommand[LotConfirmation](r =>
        RecordWaferClassified(w3.toString, "SCRAP", 2, 50.0, r))
      scrapResult.reply.error shouldBe None
      scrapResult.state.waferClassifications should contain key w3.toString
      scrapResult.state.waferClassifications(w3.toString).classification shouldBe "SCRAP"
    }

    "idempotent: repeat classification on scrapped wafer" in {
      val rLotKit = FabSagaTestConfig.createLotTestKit(reworkLotId)

      rLotKit.runCommand[LotConfirmation](r => CreateLot("RWK-IDM", Set(w3), r))
      rLotKit.runCommand[LotConfirmation](r => RecordWaferClassified(w3.toString, "SCRAP", 0, 45.0, r))

      val idemResult = rLotKit.runCommand[LotConfirmation](r =>
        RecordWaferClassified(w3.toString, "SCRAP", 0, 45.0, r))
      idemResult.reply.error shouldBe None
      idemResult.events shouldBe empty
    }
  }

  "TCC Compensate flow" should {
    "release all reservations when saga is cancelled" in {
      val sLotKit = FabSagaTestConfig.createLotTestKit(sourceLotId)
      val rLotKit = FabSagaTestConfig.createLotTestKit(reworkLotId)
      val txId = UUID.randomUUID()

      sLotKit.runCommand[LotConfirmation](r => CreateLot("PHOTO-COMP", allFiveWafers, r))
      rLotKit.runCommand[LotConfirmation](r => CreateLot("PHOTO-RWK-COMP", Set.empty, r))

      // Prepare Phase
      sLotKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(txId, Set(w3), r))
      rLotKit.runCommand[WaferAdditionConfirmation](r => ReserveAddWafer(txId, Set(w3), r))

      // Compensate
      val cancelAdd = rLotKit.runCommand[WaferAdditionConfirmation](r => CancelAddWafer(txId, r))
      cancelAdd.reply.error shouldBe None
      rLotKit.getState().incomingWafers should not contain key(txId)

      val releaseSrc = sLotKit.runCommand[WaferRemovalConfirmation](r => ReleaseReservedWafer(txId, r))
      releaseSrc.reply.error shouldBe None
      sLotKit.getState().reservedWafers should not contain key(txId)
      sLotKit.getState().waferIds should contain(w3)
    }
  }

  "Idempotent Saga" should {
    "accept repeat reservation commands without duplicate events" in {
      val sLotKit = FabSagaTestConfig.createLotTestKit(sourceLotId)
      val txId = UUID.randomUUID()

      sLotKit.runCommand[LotConfirmation](r => CreateLot("PHOTO-IDEM", allFiveWafers, r))

      sLotKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(txId, Set(w3), r))

      val retryLot = sLotKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(txId, Set(w3), r))
      retryLot.reply.error shouldBe None
      retryLot.events shouldBe empty
    }
  }
}
