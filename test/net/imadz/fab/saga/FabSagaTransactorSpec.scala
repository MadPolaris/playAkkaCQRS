package net.imadz.fab.saga

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import net.imadz.application.services.transactor.behaviors.FabSagaTransactorBehaviors
import net.imadz.domain.entities.FabSagaTransactionEntity
import net.imadz.domain.entities.FabSagaTransactionEntity._
import net.imadz.infra.saga.SagaPhase.{CommitPhase, CompensatePhase, PreparePhase}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID
import scala.concurrent.ExecutionContext

/**
 * Unit tests for FabSagaTransactor — focuses on pure functions:
 *  - createTransferSteps: TCC step generation and ordering
 *  - FabSagaTransactionEntity: state machine transitions
 *
 * Full saga orchestration (with mock coordinator + real participants)
 * is covered in PhotoCellScenarioIntegrationSpec.
 */
class FabSagaTransactorSpec extends ScalaTestWithActorTestKit(FabSagaTestConfig.testConfig)
  with AnyWordSpecLike with BeforeAndAfterEach {

  private implicit val ec: ExecutionContext = system.executionContext

  private val sourceLotId = UUID.randomUUID()
  private val targetLotId = UUID.randomUUID()
  private val waferIds = (1 to 3).map(i => UUID.randomUUID()).toSet

  // ===================================================================
  // FabSagaTransactionEntity State Machine
  // ===================================================================

  "FabSagaTransactionEntity" should {
    "transition New → Initiated on TransactionInitiated" in {
      val state = FabSagaTransactionEntity.FabSagaTransactionState(id = Some("tx-1"))
      val event = TransactionInitiated(sourceLotId, targetLotId, waferIds, System.currentTimeMillis())

      val newState = state.applyEvent(event)

      newState.status shouldBe FabSagaTransactionEntity.Status.Initiated
      newState.sourceLotId shouldBe Some(sourceLotId)
      newState.targetLotId shouldBe Some(targetLotId)
      newState.waferIds shouldBe waferIds
    }

    "transition Initiated → Completed on TransactionCompleted" in {
      val state = FabSagaTransactionEntity.FabSagaTransactionState(
        id = Some("tx-1"),
        sourceLotId = Some(sourceLotId),
        targetLotId = Some(targetLotId),
        waferIds = waferIds,
        status = FabSagaTransactionEntity.Status.Initiated
      )
      val event = TransactionCompleted("tx-1", System.currentTimeMillis())

      val newState = state.applyEvent(event)

      newState.status shouldBe FabSagaTransactionEntity.Status.Completed
    }

    "transition Initiated → Failed on TransactionFailed" in {
      val state = FabSagaTransactionEntity.FabSagaTransactionState(
        id = Some("tx-1"),
        sourceLotId = Some(sourceLotId),
        targetLotId = Some(targetLotId),
        waferIds = waferIds,
        status = FabSagaTransactionEntity.Status.Initiated
      )
      val event = TransactionFailed("tx-1", "Compensation exhausted", System.currentTimeMillis())

      val newState = state.applyEvent(event)

      newState.status shouldBe FabSagaTransactionEntity.Status.Failed("Compensation exhausted")
    }
  }

  // ===================================================================
  // createTransferSteps: TCC Step Generation & Group Ordering
  // ===================================================================

  "createTransferSteps" should {

    "generate correct number of steps for N wafers" in {
      val steps = FabSagaTransactorBehaviors.createTransferSteps(sourceLotId, targetLotId, waferIds)

      // N=3 wafers → 3*(N+2) = 3*5 = 15 steps
      steps should have size 15
    }

    "generate only lot-level steps for 1 wafer" in {
      val singleWafer = Set(UUID.randomUUID())
      val steps = FabSagaTransactorBehaviors.createTransferSteps(sourceLotId, targetLotId, singleWafer)

      // N=1 wafer → 3*(1+2) = 9 steps
      steps should have size 9
    }

    "start with Prepare phase steps" in {
      val steps = FabSagaTransactorBehaviors.createTransferSteps(sourceLotId, targetLotId, waferIds)

      val prepareSteps = steps.takeWhile(_.phase == PreparePhase)
      prepareSteps should have size 5 // 2 lot-level + 3 wafer-level

      // Group 1 (lot-level, sequential) comes first
      val group1 = prepareSteps.takeWhile(_.stepGroup == 1)
      group1 should have size 2
      group1.map(_.stepId) shouldBe Seq("reserve-source-lot", "reserve-target-lot")

      // Group 2 (wafer-level, parallel) follows
      val group2 = prepareSteps.drop(2).takeWhile(_.stepGroup == 2)
      group2 should have size 3
      group2.map(_.stepId) shouldBe Seq("reserve-wafer-0", "reserve-wafer-1", "reserve-wafer-2")
    }

    "Commit phase: wafer commits (group 2) before lot commits (group 1)" in {
      val steps = FabSagaTransactorBehaviors.createTransferSteps(sourceLotId, targetLotId, waferIds)

      val commitSteps = steps.filter(_.phase == CommitPhase)
      commitSteps should have size 5 // 3 wafer-level + 2 lot-level

      // Group 2 (wafer commits, parallel) first
      val group2 = commitSteps.takeWhile(_.stepGroup == 2)
      group2 should have size 3
      group2.map(_.stepId) shouldBe Seq("commit-wafer-0", "commit-wafer-1", "commit-wafer-2")

      // Group 1 (lot commits, sequential) follows
      val group1 = commitSteps.drop(3).takeWhile(_.stepGroup == 1)
      group1 should have size 2
      group1.map(_.stepId) shouldBe Seq("commit-source-lot", "commit-target-lot")
    }

    "Compensate phase: lot cancellations (group 1) before wafer releases (group 2)" in {
      val steps = FabSagaTransactorBehaviors.createTransferSteps(sourceLotId, targetLotId, waferIds)

      val compensateSteps = steps.filter(_.phase == CompensatePhase)
      compensateSteps should have size 5 // 2 lot-level + 3 wafer-level

      // Group 1 (lot-level, sequential) first
      val group1 = compensateSteps.takeWhile(_.stepGroup == 1)
      group1 should have size 2
      group1.map(_.stepId) shouldBe Seq("cancel-target-lot", "release-source-lot")

      // Group 2 (wafer-level, parallel) follows
      val group2 = compensateSteps.drop(2).takeWhile(_.stepGroup == 2)
      group2 should have size 3
      group2.map(_.stepId) shouldBe Seq("release-wafer-0", "release-wafer-1", "release-wafer-2")
    }

    "assign maxRetries=5 and 30s timeout to each step" in {
      val steps = FabSagaTransactorBehaviors.createTransferSteps(sourceLotId, targetLotId, waferIds)

      all(steps.map(_.maxRetries)) shouldBe 5
      all(steps.map(_.timeoutDuration.toSeconds)) shouldBe 30
    }
  }
}
