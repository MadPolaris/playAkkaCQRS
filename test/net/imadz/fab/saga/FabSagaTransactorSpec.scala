package net.imadz.fab.saga

import net.imadz.application.services.transactor._

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import net.imadz.application.services.transactor.FabSagaDefinition
import net.imadz.domain.entities.FabSagaTransactionEntity
import net.imadz.domain.entities.FabSagaTransactionEntity._
import net.imadz.infra.saga.SagaPhase.{CommitPhase, CompensatePhase, PreparePhase}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
 * Unit tests for FabSagaTransactor — 2-participant TCC (Source Lot + Target Lot).
 * WaferTransferParticipant removed; wafer lifecycle is managed by Lot aggregate.
 */
class FabSagaTransactorSpec extends ScalaTestWithActorTestKit(FabSagaTestConfig.testConfig)
  with AnyWordSpecLike with BeforeAndAfterEach {

  private implicit val ec: ExecutionContext = system.executionContext
  private implicit val scheduler: akka.actor.typed.Scheduler = system.scheduler

  private val sourceLotId = UUID.randomUUID()
  private val targetLotId = UUID.randomUUID()
  private val waferIds = (1 to 3).map(i => UUID.randomUUID()).toSet

  // ===================================================================
  // FabSagaTransactionEntity State Machine
  // ===================================================================

  "FabSagaTransactionEntity" should {
    "transition New -> Initiated on TransactionInitiated" in {
      val state = FabSagaTransactionEntity.FabSagaTransactionState(id = Some("tx-1"))
      val event = TransactionInitiated(sourceLotId, targetLotId, waferIds, System.currentTimeMillis())

      val newState = state.applyEvent(event)

      newState.status shouldBe FabSagaTransactionEntity.Status.Initiated
      newState.sourceLotId shouldBe Some(sourceLotId)
      newState.targetLotId shouldBe Some(targetLotId)
      newState.waferIds shouldBe waferIds
    }

    "transition Initiated -> Completed on TransactionCompleted" in {
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

    "transition Initiated -> Failed on TransactionFailed" in {
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
  // FabSagaDefinition.expand: 2-participant TCC Step Generation (v3 DSL)
  // ===================================================================

  private def expandedSteps = {
    val args = FabSagaDefinition.FabSagaArgs(
      sourceLotId = sourceLotId.toString,
      targetLotId = targetLotId.toString,
      waferIds = waferIds.map(_.toString).toList,
      waferNames = Nil)
    FabSagaDefinition.definition.expand(args).get
  }

  "FabSagaDefinition.expand (2-participant TCC)" should {

    "generate 6 steps total (2 lot-level steps x prepare/commit/compensate)" in {
      expandedSteps should have size 6
    }

    "prepare phase: reserve both lots" in {
      val prepareSteps = expandedSteps.filter(_.phase == PreparePhase)
      prepareSteps should have size 2
      prepareSteps.map(_.stepId) shouldBe Seq("source-lot", "target-lot")
    }

    "commit phase: commit both lots" in {
      val commitSteps = expandedSteps.filter(_.phase == CommitPhase)
      commitSteps should have size 2
      commitSteps.map(_.stepId) shouldBe Seq("source-lot", "target-lot")
    }

    "compensate phase: both lots" in {
      val compensateSteps = expandedSteps.filter(_.phase == CompensatePhase)
      compensateSteps should have size 2
      compensateSteps.map(_.stepId) shouldBe Seq("source-lot", "target-lot")
    }

    "assign maxRetries=5 and 30s timeout to each step" in {
      all(expandedSteps.map(_.maxRetries)) shouldBe 5
      all(expandedSteps.map(_.timeoutDuration.toSeconds)) shouldBe 30
    }
  }
}
