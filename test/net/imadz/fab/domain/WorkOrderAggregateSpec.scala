package net.imadz.fab.domain

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.Behavior
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import net.imadz.application.aggregates.WorkOrderProtocol._
import net.imadz.application.aggregates.behaviors.WorkOrderBehaviors
import net.imadz.domain.entities.WorkOrderEntity
import net.imadz.domain.entities.WorkOrderEntity._
import net.imadz.fab.saga.FabSagaTestConfig
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID

class WorkOrderAggregateSpec extends ScalaTestWithActorTestKit(FabSagaTestConfig.testConfig)
  with AnyWordSpecLike with BeforeAndAfterEach {

  private var workOrderId = UUID.randomUUID().toString
  private var woTestKit = createWorkOrderTestKit(workOrderId)

  private def createWorkOrderTestKit(id: String): EventSourcedBehaviorTestKit[WorkOrderCommand, WorkOrderEvent, WorkOrderState] = {
    val behavior: Behavior[WorkOrderCommand] = Behaviors.setup[WorkOrderCommand] { ctx =>
      EventSourcedBehavior(
        persistenceId = PersistenceId("WorkOrder", id),
        emptyState = WorkOrderEntity.empty,
        commandHandler = WorkOrderBehaviors.apply(id, ctx),
        eventHandler = WorkOrderEntity.handleEvent
      )
    }
    EventSourcedBehaviorTestKit[WorkOrderCommand, WorkOrderEvent, WorkOrderState](system, behavior)
  }

  override def beforeEach(): Unit = {
    workOrderId = UUID.randomUUID().toString
    woTestKit = createWorkOrderTestKit(workOrderId)
  }

  // ===================================================================
  // Goal 4: Event-driven state machine (CreateWorkOrder → Executing)
  // ===================================================================

  "WorkOrder aggregate" should {

    "transition from Idle to Executing on CreateWorkOrder" in {
      val result = woTestKit.runCommand[WorkOrderConfirmation](replyTo =>
        CreateWorkOrder("PROD-A", Seq("WAFER-1", "WAFER-2", "WAFER-3"), replyTo = replyTo))

      result.reply.workOrderId shouldBe workOrderId
      result.reply.phase shouldBe "Executing"
      result.events should have size 1
      result.events.head shouldBe a[WorkOrderCreated]
      result.state shouldBe a[Executing]
      result.state.asInstanceOf[Executing].productId shouldBe "PROD-A"
      result.state.asInstanceOf[Executing].totalLots shouldBe 1
      result.state.asInstanceOf[Executing].completedLotCount shouldBe 0
    }

    "set totalLots from CreateWorkOrder" in {
      val result = woTestKit.runCommand[WorkOrderConfirmation](replyTo =>
        CreateWorkOrder("PROD-A", Seq("WAFER-1"), totalLots = 3, replyTo = replyTo))

      result.state.asInstanceOf[Executing].totalLots shouldBe 3
    }

    "reject CreateWorkOrder when already active (idempotent guard)" in {
      woTestKit.runCommand[WorkOrderConfirmation](replyTo =>
        CreateWorkOrder("PROD-A", Seq("WAFER-1"), replyTo = replyTo))

      val result = woTestKit.runCommand[WorkOrderConfirmation](replyTo =>
        CreateWorkOrder("PROD-B", Seq("WAFER-2"), replyTo = replyTo))

      result.reply.phase shouldBe "AlreadyActive"
      result.events shouldBe empty
    }

    // ===================================================================
    // Goal 2: 0 zombie WorkOrders — RecordLotCompleted → Completed
    // ===================================================================

    "RecordLotCompleted increments completedLotCount" in {
      woTestKit.runCommand[WorkOrderConfirmation](replyTo =>
        CreateWorkOrder("PROD-A", Seq("WAFER-1"), totalLots = 3, replyTo = replyTo))

      val result = woTestKit.runCommand(RecordLotCompleted(workOrderId, "LOT-1", passCount = 5, scrapCount = 0, reworkCount = 0))

      result.events should have size 1
      result.events.head shouldBe a[LotCompletionRecorded]
      val s = result.state.asInstanceOf[Executing]
      s.completedLotCount shouldBe 1
      s.completedLotIds should contain("LOT-1")
      s.accumPassCount shouldBe 5
      s.accumScrapCount shouldBe 0
      s.accumReworkCount shouldBe 0
    }

    "RecordLotCompleted × N (all done) → auto-transition to Completed" in {
      woTestKit.runCommand[WorkOrderConfirmation](replyTo =>
        CreateWorkOrder("PROD-A", Seq("WAFER-1"), totalLots = 2, replyTo = replyTo))

      woTestKit.runCommand(RecordLotCompleted(workOrderId, "LOT-1", passCount = 3, scrapCount = 1, reworkCount = 0))
      val result = woTestKit.runCommand(RecordLotCompleted(workOrderId, "LOT-2", passCount = 4, scrapCount = 0, reworkCount = 1))

      result.events should have size 2
      result.events.head shouldBe a[LotCompletionRecorded]
      result.events(1) shouldBe a[WorkOrderCompleted]
      val c = result.state.asInstanceOf[Completed]
      c.passCount shouldBe 7   // 3 + 4
      c.scrapCount shouldBe 1  // 1 + 0
      c.reworkCount shouldBe 1 // 0 + 1
    }

    "RecordLotCompleted: duplicate lotId is idempotent no-op" in {
      woTestKit.runCommand[WorkOrderConfirmation](replyTo =>
        CreateWorkOrder("PROD-A", Seq("WAFER-1"), totalLots = 3, replyTo = replyTo))

      woTestKit.runCommand(RecordLotCompleted(workOrderId, "LOT-1", passCount = 5, scrapCount = 0, reworkCount = 0))

      // Duplicate for same lotId
      val result = woTestKit.runCommand(RecordLotCompleted(workOrderId, "LOT-1", passCount = 99, scrapCount = 99, reworkCount = 99))

      result.events shouldBe empty // no-op
      val s = result.state.asInstanceOf[Executing]
      s.completedLotCount shouldBe 1 // unchanged
      s.accumPassCount shouldBe 5   // not 99+5
    }

    // ===================================================================
    // Goal 2: 0 zombie WorkOrders — RecordLotFailed → Failed
    // ===================================================================

    "RecordLotFailed transitions Executing → Failed" in {
      woTestKit.runCommand[WorkOrderConfirmation](replyTo =>
        CreateWorkOrder("PROD-A", Seq("WAFER-1"), replyTo = replyTo))

      val result = woTestKit.runCommand(RecordLotFailed(workOrderId, "LOT-1", "CD out of spec", "Measure"))

      result.events should have size 1
      result.events.head shouldBe a[WorkOrderFailed]
      result.state shouldBe a[Failed]
      val f = result.state.asInstanceOf[Failed]
      f.error should include("LOT-1")
      f.error should include("Measure")
      f.error should include("CD out of spec")
    }

    "RecordLotFailed after some completions → Failed (not stuck)" in {
      woTestKit.runCommand[WorkOrderConfirmation](replyTo =>
        CreateWorkOrder("PROD-A", Seq("WAFER-1"), totalLots = 3, replyTo = replyTo))

      // Complete LOT-1 successfully
      woTestKit.runCommand(RecordLotCompleted(workOrderId, "LOT-1", passCount = 5, scrapCount = 0, reworkCount = 0))

      // LOT-2 fails
      val result = woTestKit.runCommand(RecordLotFailed(workOrderId, "LOT-2", "equipment timeout", "Process"))

      result.state shouldBe a[Failed]
    }

    "RecordLotCompleted when not in Executing → no-op" in {
      val result = woTestKit.runCommand(RecordLotCompleted(workOrderId, "LOT-X", passCount = 1, scrapCount = 0, reworkCount = 0))

      result.events shouldBe empty
      result.state shouldBe Idle
    }

    "RecordLotFailed when not in Executing → no-op" in {
      val result = woTestKit.runCommand(RecordLotFailed(workOrderId, "LOT-X", "reason", "stage"))

      result.events shouldBe empty
      result.state shouldBe Idle
    }

    // ===================================================================
    // Goal 4: Pure event handler consistency
    // ===================================================================

    "handleEvent: WorkOrderCreated → Executing" in {
      val state = WorkOrderEntity.handleEvent(Idle,
        WorkOrderCreated("WO-1", "P-A", Seq("W-1"), waferCount = 1, routeRef = None, totalLots = 2))

      state shouldBe a[Executing]
      val e = state.asInstanceOf[Executing]
      e.workOrderId shouldBe "WO-1"
      e.productId shouldBe "P-A"
      e.totalLots shouldBe 2
    }

    "handleEvent: LotCompletionRecorded → increments counters" in {
      val exec = Executing("WO-1", "P-A", Seq("W-1"), totalLots = 3)
      val state = WorkOrderEntity.handleEvent(exec,
        LotCompletionRecorded("WO-1", "L-1", passCount = 2, scrapCount = 0, reworkCount = 1))

      state shouldBe a[Executing]
      val e = state.asInstanceOf[Executing]
      e.completedLotCount shouldBe 1
      e.accumPassCount shouldBe 2
      e.accumReworkCount shouldBe 1
      e.completedLotIds should contain("L-1")
    }

    "handleEvent: LotCompletionRecorded duplicate → idempotent" in {
      val exec = Executing("WO-1", "P-A", Seq("W-1"), totalLots = 3,
        completedLotCount = 1, completedLotIds = Set("L-1"), accumPassCount = 2)
      val state = WorkOrderEntity.handleEvent(exec,
        LotCompletionRecorded("WO-1", "L-1", passCount = 99, scrapCount = 99, reworkCount = 99))

      val e = state.asInstanceOf[Executing]
      e.completedLotCount shouldBe 1  // unchanged
      e.accumPassCount shouldBe 2     // unchanged
    }

    "handleEvent: WorkOrderCompleted → Completed state" in {
      val state = WorkOrderEntity.handleEvent(Idle,
        WorkOrderCompleted(passCount = 10, scrapCount = 2, reworkCount = 3))

      state shouldBe a[Completed]
      val c = state.asInstanceOf[Completed]
      c.passCount shouldBe 10
      c.scrapCount shouldBe 2
      c.reworkCount shouldBe 3
    }

    "handleEvent: WorkOrderFailed → Failed state" in {
      val state = WorkOrderEntity.handleEvent(Idle,
        WorkOrderFailed("equipment timeout at Measure"))

      state shouldBe a[Failed]
      state.asInstanceOf[Failed].error shouldBe "equipment timeout at Measure"
    }
  }
}
