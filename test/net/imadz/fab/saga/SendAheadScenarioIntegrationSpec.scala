package net.imadz.fab.saga

import net.imadz.application.services.transactor._

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import net.imadz.application.aggregates.LotProtocol._
import net.imadz.domain.entities.LotEntity.{Active => LotActive, _}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID

/**
 * Send-Ahead Pilot scenario: 2-participant TCC (Source Lot + Target Lot).
 *   1. Create source lot with 5 wafers
 *   2. Split 1 wafer (W1) -> Pilot lot via TCC
 *   3. Merge W1 back to source lot after pilot passes
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
  private val allFiveWafers = Map(w1 -> "W1", w2 -> "W2", w3 -> "W3", w4 -> "W4", w5 -> "W5")
  private val pilotWafer = Set(w1)
  private val pilotWaferName = Set("W1")

  "Send-Ahead Pilot (PASS -> Merge)" should {
    "create source lot with 5 wafers and empty pilot child lot" in {
      val sKit = FabSagaTestConfig.createLotTestKit(sourceLotId)
      val pKit = FabSagaTestConfig.createLotTestKit(pilotLotId)

      val src = sKit.runCommand[LotConfirmation](r => CreateLot("SEND-AHEAD-SRC", allFiveWafers, r))
      src.reply.error shouldBe None
      src.state.phase shouldBe LotActive
      src.state.waferIds should have size 5

      val plt = pKit.runCommand[LotConfirmation](r => CreateLot("SEND-AHEAD-PILOT", Map.empty, r))
      plt.reply.error shouldBe None
      plt.state.phase shouldBe LotActive
      plt.state.waferIds shouldBe empty
    }

    "split W1 from source to pilot lot via 2-participant TCC" in {
      val sKit = FabSagaTestConfig.createLotTestKit(sourceLotId)
      val pKit = FabSagaTestConfig.createLotTestKit(pilotLotId)
      val txId = UUID.randomUUID()

      sKit.runCommand[LotConfirmation](r => CreateLot("SEND-AHEAD-SRC", allFiveWafers, r))
      pKit.runCommand[LotConfirmation](r => CreateLot("SEND-AHEAD-PILOT", Map.empty, r))

      // --- Prepare Phase ---
      sKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(txId, pilotWafer, pilotWaferName, r))
      pKit.runCommand[WaferAdditionConfirmation](r => ReserveAddWafer(txId, pilotWafer, r))

      sKit.getState().reservedWafers should contain key txId
      pKit.getState().incomingWafers should contain key txId

      // --- Commit Phase ---
      sKit.runCommand[WaferRemovalConfirmation](r => CommitWaferRemoval(txId, r))
      pKit.runCommand[WaferAdditionConfirmation](r => CommitAddWafer(txId, r))

      sKit.getState().waferIds should have size 4
      sKit.getState().waferIds should not contain w1
      pKit.getState().waferIds shouldBe pilotWafer
      pKit.getState().waferIds should contain(w1)
    }

    "merge W1 back to source lot after pilot passes" in {
      val sKit = FabSagaTestConfig.createLotTestKit(sourceLotId)
      val pKit = FabSagaTestConfig.createLotTestKit(pilotLotId)
      val mergeTxId = UUID.randomUUID()

      // Setup: source with 4 wafers, pilot with W1
      sKit.runCommand[LotConfirmation](r => CreateLot("SEND-AHEAD-SRC", Map(w2 -> "W2", w3 -> "W3", w4 -> "W4", w5 -> "W5"), r))
      pKit.runCommand[LotConfirmation](r => CreateLot("SEND-AHEAD-PILOT", Map(w1 -> "W1"), r))

      // --- Merge (Pilot -> Source) ---
      sKit.runCommand[WaferAdditionConfirmation](r => ReserveAddWafer(mergeTxId, pilotWafer, r))
      pKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(mergeTxId, pilotWafer, pilotWaferName, r))

      pKit.runCommand[WaferRemovalConfirmation](r => CommitWaferRemoval(mergeTxId, r))
      sKit.runCommand[WaferAdditionConfirmation](r => CommitAddWafer(mergeTxId, r))

      // Verify: source has all 5 wafers again
      sKit.getState().waferIds should have size 5
      sKit.getState().waferIds should contain(w1)
      pKit.getState().waferIds shouldBe empty
    }
  }

  "Send-Ahead Pilot (FAIL -> Scrap)" should {
    "record SCRAP classification after pilot fails" in {
      val sKit = FabSagaTestConfig.createLotTestKit(sourceLotId)
      val pKit = FabSagaTestConfig.createLotTestKit(pilotLotId)

      // Setup: source with 4, pilot with W1
      sKit.runCommand[LotConfirmation](r => CreateLot("SEND-AHEAD-SRC", Map(w2 -> "W2", w3 -> "W3", w4 -> "W4", w5 -> "W5"), r))
      pKit.runCommand[LotConfirmation](r => CreateLot("SEND-AHEAD-PILOT", Map(w1 -> "W1"), r))

      // Pilot FAIL -> record SCRAP classification
      pKit.runCommand[LotConfirmation](r =>
        RecordWaferClassified(w1, "SCRAP", 0, 50.0, r))
      pKit.getState().waferClassifications(w1).classification shouldBe "SCRAP"

      // Verify: source still has 4 wafers, no merge happened
      sKit.getState().waferIds should have size 4
      sKit.getState().waferIds should not contain w1
    }
  }

  "Send-Ahead idempotency" should {
    "accept repeated reserve/commit without duplicate events" in {
      val sKit = FabSagaTestConfig.createLotTestKit(sourceLotId)
      val pKit = FabSagaTestConfig.createLotTestKit(pilotLotId)
      val txId = UUID.randomUUID()

      sKit.runCommand[LotConfirmation](r => CreateLot("SEND-AHEAD-IDM", allFiveWafers, r))
      pKit.runCommand[LotConfirmation](r => CreateLot("SEND-AHEAD-PLT", Map.empty, r))

      // First reserve
      sKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(txId, pilotWafer, pilotWaferName, r))
      pKit.runCommand[WaferAdditionConfirmation](r => ReserveAddWafer(txId, pilotWafer, r))

      // Repeat reserve (idempotent)
      val retrySrc = sKit.runCommand[WaferRemovalConfirmation](r => ReserveWaferRemoval(txId, pilotWafer, pilotWaferName, r))
      retrySrc.reply.error shouldBe None
      retrySrc.events shouldBe empty

      // Commit
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
