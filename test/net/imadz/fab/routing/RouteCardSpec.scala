package net.imadz.fab.routing

import net.imadz.domain.routing._
import net.imadz.application.routing._

import net.imadz.application.aggregates.LotProtocol._
import net.imadz.common.Id
import net.imadz.common.CommonTypes.iMadzError
import net.imadz.domain.entities.LotEntity
import net.imadz.domain.entities.LotEntity._
import net.imadz.domain.entities.behaviors.LotEventHandler
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RouteCardSpec extends AnyFlatSpec with Matchers {

  def emptyState: LotState = LotEntity.empty(Id.gen)

  "RouteCardAssigned event" should "set routeCard on LotState" in {
    val state = emptyState.copy(phase = Active)
    val steps = Seq("LoadFoup", "Transport:STOCKER->LITHO", "RunRecipe:LITHO-01:LITHO-28-001")
    val event = RouteCardAssigned(steps, Some("LOGIC-28NM:v3"), "InitialRoute", 1000L)

    val updated = LotEventHandler.apply(state, event)
    updated.routeCard shouldBe defined
    updated.routeCard.get.steps shouldBe steps
    updated.routeCard.get.currentStepIndex shouldBe 0
    updated.routeCard.get.reason shouldBe "InitialRoute"
    updated.routeCard.get.sourcedFrom shouldBe Some("LOGIC-28NM:v3")
  }

  "RouteCardStepAdvanced event" should "advance currentStepIndex" in {
    val steps = Seq("LoadFoup", "Transport:STOCKER->LITHO", "RunRecipe:LITHO-01:REWORK-001")
    val state = emptyState.copy(
      phase = Active,
      routeCard = Some(RouteCard(steps, 0, Some("LOGIC-28NM:v3"), "InitialRoute", 1000L))
    )

    val updated = LotEventHandler.apply(state, RouteCardStepAdvanced(1))
    updated.routeCard.get.currentStepIndex shouldBe 1
  }

  it should "not regress step index" in {
    val state = emptyState.copy(
      phase = Active,
      routeCard = Some(RouteCard(Seq("A", "B", "C"), 2, None, "test", 1000L))
    )
    val updated = LotEventHandler.apply(state, RouteCardStepAdvanced(1))
    // Event is applied anyway; invariant check happens at command level
    updated.routeCard.get.currentStepIndex shouldBe 1
  }

  "AssignRouteCard invariant" should "reject empty steps" in {
    import net.imadz.domain.invariants.LotInvariants.AssignRouteCardRule
    val state = emptyState.copy(phase = Empty)
    val result = AssignRouteCardRule.apply(state, (Seq.empty, None, "test"))
    result.isLeft shouldBe true
    result.left.get.code shouldBe "ROUTECARD_001"
  }

  it should "reject assignment in wrong phase" in {
    import net.imadz.domain.invariants.LotInvariants.AssignRouteCardRule
    val state = emptyState.copy(phase = Sealed)
    val result = AssignRouteCardRule.apply(state, (Seq("A"), None, "test"))
    result.isLeft shouldBe true
    result.left.get.code shouldBe "ROUTECARD_002"
  }

  it should "allow assignment in Empty phase" in {
    import net.imadz.domain.invariants.LotInvariants.AssignRouteCardRule
    val state = emptyState.copy(phase = Empty)
    val result = AssignRouteCardRule.apply(state, (Seq("A"), None, "test"))
    result.isRight shouldBe true
  }

  it should "allow assignment in Active phase" in {
    import net.imadz.domain.invariants.LotInvariants.AssignRouteCardRule
    val state = emptyState.copy(phase = Active)
    val result = AssignRouteCardRule.apply(state, (Seq("LoadFoup"), Some("LOGIC-28NM:v3"), "InitialRoute"))
    result.isRight shouldBe true
    val events = result.right.get
    events.head shouldBe a[RouteCardAssigned]
    events.head.asInstanceOf[RouteCardAssigned].steps shouldBe Seq("LoadFoup")
  }

  "AdvanceRouteCardStep invariant" should "reject when no RouteCard assigned" in {
    import net.imadz.domain.invariants.LotInvariants.AdvanceRouteCardStepRule
    val state = emptyState.copy(phase = Active)
    val result = AdvanceRouteCardStepRule.apply(state, 0)
    result.isLeft shouldBe true
    result.left.get.code shouldBe "ROUTECARD_003"
  }

  it should "reject step index out of bounds" in {
    import net.imadz.domain.invariants.LotInvariants.AdvanceRouteCardStepRule
    val state = emptyState.copy(
      phase = Active,
      routeCard = Some(RouteCard(Seq("A", "B", "C"), 0, None, "test", 1000L))
    )
    val result = AdvanceRouteCardStepRule.apply(state, 5)
    result.isLeft shouldBe true
    result.left.get.code shouldBe "ROUTECARD_004"
  }

  it should "reject already-advanced step" in {
    import net.imadz.domain.invariants.LotInvariants.AdvanceRouteCardStepRule
    val state = emptyState.copy(
      phase = Active,
      routeCard = Some(RouteCard(Seq("A", "B", "C"), 2, None, "test", 1000L))
    )
    val result = AdvanceRouteCardStepRule.apply(state, 1)
    result.isLeft shouldBe true
    result.left.get.code shouldBe "ROUTECARD_005"
  }

  it should "allow advancing to next step" in {
    import net.imadz.domain.invariants.LotInvariants.AdvanceRouteCardStepRule
    val state = emptyState.copy(
      phase = Active,
      routeCard = Some(RouteCard(Seq("A", "B", "C"), 1, None, "test", 1000L))
    )
    val result = AdvanceRouteCardStepRule.apply(state, 2)
    result.isRight shouldBe true
    result.right.get.head.asInstanceOf[RouteCardStepAdvanced].stepIndex shouldBe 2
  }

  it should "allow advancing from index 0 to 1" in {
    import net.imadz.domain.invariants.LotInvariants.AdvanceRouteCardStepRule
    val state = emptyState.copy(
      phase = Active,
      routeCard = Some(RouteCard(Seq("A", "B"), 0, None, "test", 1000L))
    )
    val result = AdvanceRouteCardStepRule.apply(state, 1)
    result.isRight shouldBe true
  }
}
