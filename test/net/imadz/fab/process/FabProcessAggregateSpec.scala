package net.imadz.fab.process

import net.imadz.application.aggregates._

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import net.imadz.domain.entities.FabProcessEntity
import net.imadz.fab.saga.FabSagaTestConfig
import org.scalatest.wordspec.AnyWordSpecLike

class FabProcessAggregateSpec extends ScalaTestWithActorTestKit(FabSagaTestConfig.testConfig)
  with AnyWordSpecLike {

  "FabProcessEntity" should {
    "initialize with empty state" in {
      val state = FabProcessEntity.empty("test-process-1")
      state.processId shouldBe "test-process-1"
      state.phase shouldBe FabProcessEntity.ProcessCreated
      state.lotId shouldBe ""
      state.waferIds shouldBe empty
      state.passCount shouldBe 0
    }

    "transition from Created to Active on ProcessStarted" in {
      val state = FabProcessEntity.empty("test-process-2")
      val event = FabProcessEntity.ProcessStarted("LOT-1", Set("W-1", "W-2"), 2)
      val handler = net.imadz.domain.entities.behaviors.FabProcessEventHandler.apply
      val newState = handler(state, event)
      newState.phase shouldBe FabProcessEntity.ProcessActive
      newState.lotId shouldBe "LOT-1"
      newState.waferIds shouldBe Set("W-1", "W-2")
    }
  }
}
