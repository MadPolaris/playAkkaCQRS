package net.imadz.fab.domain

import net.imadz.application.aggregates._

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

class WorkOrderRecoverySpec extends ScalaTestWithActorTestKit(FabSagaTestConfig.testConfig)
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

  "WorkOrder recovery" should {

    // P2-R1
    "restart and preserve Executing state with partial completions" in {
      woTestKit.runCommand[WorkOrderConfirmation](replyTo =>
        CreateWorkOrder("PROD-A", Seq("WAFER-1", "WAFER-2", "WAFER-3"), totalLots = 3, replyTo = replyTo))

      woTestKit.runCommand(RecordLotCompleted(workOrderId, "LOT-1", passCount = 3, scrapCount = 0, reworkCount = 0))
      woTestKit.runCommand(RecordLotCompleted(workOrderId, "LOT-2", passCount = 4, scrapCount = 0, reworkCount = 1))

      // Simulate crash + restart
      woTestKit.restart()

      val state = woTestKit.getState().asInstanceOf[Executing]
      state.completedLotCount shouldBe 2
      state.completedLotIds should contain allOf ("LOT-1", "LOT-2")
      state.accumPassCount shouldBe 7

      // Complete remaining lot
      val result = woTestKit.runCommand(RecordLotCompleted(workOrderId, "LOT-3", passCount = 2, scrapCount = 1, reworkCount = 0))
      result.state shouldBe a[Completed]
      result.state.asInstanceOf[Completed].passCount shouldBe 9
    }

    // P2-R2
    "restart and preserve Failed state" in {
      woTestKit.runCommand[WorkOrderConfirmation](replyTo =>
        CreateWorkOrder("PROD-A", Seq("WAFER-1"), replyTo = replyTo))

      woTestKit.runCommand(RecordLotFailed(workOrderId, "LOT-1", "CD out of spec", "Measure"))

      woTestKit.restart()

      val state = woTestKit.getState()
      state shouldBe a[Failed]
      state.asInstanceOf[Failed].error should include("CD out of spec")

      // RecordLotCompleted should be no-op in Failed state
      val result = woTestKit.runCommand(RecordLotCompleted(workOrderId, "LOT-2", passCount = 5, scrapCount = 0, reworkCount = 0))
      result.events shouldBe empty
      result.state shouldBe a[Failed]
    }

    // P2-R3
    "handle duplicate events idempotently after restart" in {
      woTestKit.runCommand[WorkOrderConfirmation](replyTo =>
        CreateWorkOrder("PROD-A", Seq("WAFER-1"), totalLots = 3, replyTo = replyTo))

      woTestKit.runCommand(RecordLotCompleted(workOrderId, "LOT-1", passCount = 5, scrapCount = 0, reworkCount = 0))

      woTestKit.restart()

      // Duplicate event (simulating at-least-once Projection delivery)
      val result = woTestKit.runCommand(RecordLotCompleted(workOrderId, "LOT-1", passCount = 99, scrapCount = 99, reworkCount = 99))
      result.events shouldBe empty
      val s = result.state.asInstanceOf[Executing]
      s.completedLotCount shouldBe 1
      s.accumPassCount shouldBe 5 // not 99

      // Complete remaining
      woTestKit.runCommand(RecordLotCompleted(workOrderId, "LOT-2", passCount = 2, scrapCount = 0, reworkCount = 0))
      val finalResult = woTestKit.runCommand(RecordLotCompleted(workOrderId, "LOT-3", passCount = 3, scrapCount = 0, reworkCount = 0))
      finalResult.state shouldBe a[Completed]
    }
  }
}
