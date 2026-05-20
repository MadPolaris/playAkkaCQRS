package net.imadz.fab.chain

import net.imadz.application.chain._

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
import scala.util.Random

class ConcurrentWorkOrderChaosSpec extends ScalaTestWithActorTestKit(FabSagaTestConfig.testConfig)
  with AnyWordSpecLike with BeforeAndAfterEach {

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

  override def afterEach(): Unit = {
    // No-op: each test creates its own kits
  }

  "Concurrent WorkOrders" should {

    // P2.1: 3 concurrent WorkOrders, all succeed with no faults
    "P2.1: 3 concurrent WorkOrders complete successfully with no faults" in {
      val wo1 = createWorkOrderTestKit(UUID.randomUUID().toString)
      val wo2 = createWorkOrderTestKit(UUID.randomUUID().toString)
      val wo3 = createWorkOrderTestKit(UUID.randomUUID().toString)

      // Create all 3
      Seq(wo1, wo2, wo3).foreach { wo =>
        wo.runCommand[WorkOrderConfirmation](replyTo =>
          CreateWorkOrder("PROD-A", Seq("WAFER-1"), totalLots = 2, replyTo = replyTo))
      }

      // Complete LOT-1 for all 3
      Seq(wo1, wo2, wo3).zipWithIndex.foreach { case (wo, i) =>
        val r = wo.runCommand(RecordLotCompleted(s"wo-$i", s"LOT-1", passCount = 3 + i, scrapCount = 0, reworkCount = 0))
        r.state shouldBe a[Executing]
        r.state.asInstanceOf[Executing].completedLotCount shouldBe 1
      }

      // Complete LOT-2 for all 3 — should transition to Completed
      Seq(wo1, wo2, wo3).zipWithIndex.foreach { case (wo, i) =>
        val r = wo.runCommand(RecordLotCompleted(s"wo-$i", s"LOT-2", passCount = 2 + i, scrapCount = 1, reworkCount = 0))
        r.state shouldBe a[Completed]
      }

      // Verify final states (zipWithIndex: i=0,1,2)
      wo1.getState().asInstanceOf[Completed].passCount shouldBe 5  // LOT-1:3 + LOT-2:2
      wo2.getState().asInstanceOf[Completed].passCount shouldBe 7  // LOT-1:4 + LOT-2:3
      wo3.getState().asInstanceOf[Completed].passCount shouldBe 9  // LOT-1:5 + LOT-2:4
    }

    // P2.2: 5 concurrent WorkOrders, some with failures
    "P2.2: 5 concurrent WorkOrders with fault injection (20% failure rate)" in {
      val rng = new Random(42) // deterministic seed
      val workOrders = (1 to 5).map(_ => createWorkOrderTestKit(UUID.randomUUID().toString))

      // Create all with totalLots = 3
      workOrders.foreach { wo =>
        wo.runCommand[WorkOrderConfirmation](replyTo =>
          CreateWorkOrder("PROD-B", Seq("WAFER-1", "WAFER-2"), totalLots = 3, replyTo = replyTo))
      }

      var completedCount = 0
      var failedCount = 0

      // Process 3 lots per WorkOrder
      workOrders.zipWithIndex.foreach { case (wo, idx) =>
        (1 to 3).foreach { lotNum =>
          // 20% chance of failure
          if (rng.nextDouble() < 0.2) {
            val r = wo.runCommand(RecordLotFailed(s"wo-$idx", s"LOT-$lotNum", "random failure", "Process"))
            r.state match {
              case f: Failed =>
                f.error should include("random failure")
                failedCount += 1
              case _ => // may already be Failed from previous lot
            }
          } else {
            val r = wo.runCommand(RecordLotCompleted(s"wo-$idx", s"LOT-$lotNum",
              passCount = 3 + rng.nextInt(3), scrapCount = rng.nextInt(2), reworkCount = rng.nextInt(2)))
            r.state match {
              case c: Completed =>
                completedCount += 1
              case e: Executing =>
                // Still executing, check progress
                e.completedLotCount should be > 0
              case _ => // Failed state — already counted
            }
          }
        }
      }

      // At least some should have completed or failed
      // (with 15 total events and 20% failure rate, expect ~3 failures, ~12 completions)
      val terminalStates = workOrders.map(wo => wo.getState())
      val completedStates = terminalStates.count(_.isInstanceOf[Completed])
      val failedStates = terminalStates.count(_.isInstanceOf[Failed])

      // All should be in terminal state (Completed or Failed)
      (completedStates + failedStates) shouldBe 5
    }

    // P2.3: Saga TCC concurrent conflict — test duplicate lot idempotency
    "P2.3: Duplicate RecordLotCompleted across concurrent deliveries is idempotent" in {
      val wo = createWorkOrderTestKit(UUID.randomUUID().toString)

      wo.runCommand[WorkOrderConfirmation](replyTo =>
        CreateWorkOrder("PROD-C", Seq("WAFER-1"), totalLots = 2, replyTo = replyTo))

      // First delivery
      val r1 = wo.runCommand(RecordLotCompleted("wo-1", "LOT-1", passCount = 5, scrapCount = 0, reworkCount = 0))
      r1.events should have size 1
      r1.state.asInstanceOf[Executing].completedLotCount shouldBe 1

      // Duplicate delivery (simulating at-least-once projection redelivery)
      val r2 = wo.runCommand(RecordLotCompleted("wo-1", "LOT-1", passCount = 5, scrapCount = 0, reworkCount = 0))
      r2.events shouldBe empty // idempotent
      r2.state.asInstanceOf[Executing].completedLotCount shouldBe 1

      // Complete second lot with duplicate delivery
      val r3 = wo.runCommand(RecordLotCompleted("wo-1", "LOT-2", passCount = 3, scrapCount = 1, reworkCount = 0))
      r3.state shouldBe a[Completed]
      r3.state.asInstanceOf[Completed].passCount shouldBe 8

      // Duplicate delivery after completion should be no-op
      val r4 = wo.runCommand(RecordLotCompleted("wo-1", "LOT-2", passCount = 99, scrapCount = 99, reworkCount = 99))
      r4.events shouldBe empty
      r4.state shouldBe a[Completed]
    }

    // P2.4: Mixed scenario concurrency — different products and lot counts
    "P2.4: Mixed scenario concurrency with different products and lot counts" in {
      val scenarios = Seq(
        ("PROD-A", 3), // 3 lots
        ("PROD-B", 1), // single lot
        ("PROD-C", 5)  // 5 lots
      )

      val kits = scenarios.map { case (prod, lots) =>
        val kit = createWorkOrderTestKit(UUID.randomUUID().toString)
        kit.runCommand[WorkOrderConfirmation](replyTo =>
          CreateWorkOrder(prod, Seq(s"WAFER-$prod"), totalLots = lots, replyTo = replyTo))
        (kit, prod, lots)
      }

      // Complete lots for each kit
      kits.foreach { case (kit, prod, lots) =>
        (1 to lots).foreach { lotNum =>
          if (lotNum < lots) {
            // Intermediate lot completions should stay in Executing
            val r = kit.runCommand(RecordLotCompleted(s"$prod-$lotNum", s"LOT-$lotNum",
              passCount = 2, scrapCount = 0, reworkCount = 0))
            r.state shouldBe a[Executing]
            r.state.asInstanceOf[Executing].completedLotCount shouldBe lotNum
          } else {
            // Final lot completion should transition to Completed
            val r = kit.runCommand(RecordLotCompleted(s"$prod-$lotNum", s"LOT-$lotNum",
              passCount = 2, scrapCount = 1, reworkCount = 0))
            r.state shouldBe a[Completed]
          }
        }
      }

      // Verify final states (only final lot carries scrapCount=1)
      kits.foreach { case (kit, prod, lots) =>
        val state = kit.getState()
        state shouldBe a[Completed]
        val completed = state.asInstanceOf[Completed]
        completed.passCount shouldBe lots * 2
        completed.scrapCount shouldBe 1
      }
    }
  }
}
