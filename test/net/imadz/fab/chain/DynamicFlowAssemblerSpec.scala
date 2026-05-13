package net.imadz.fab.chain

import net.imadz.fab.model.{EquipmentArea, RoutingStep}
import net.imadz.fab.scenario.DecisionConfig
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration._

class DynamicFlowAssemblerSpec extends AnyWordSpec with Matchers {

  val defaultSpec = DecisionConfig(
    lowerSpecNm = 28.0,
    upperSpecNm = 34.0,
    borderlineWindowNm = 2.0,
    maxReworkCount = 3,
    reworkRecipeId = "REWORK-LITHO-001"
  )

  val exampleStep = RoutingStep(
    stepId = "op-030",
    equipmentArea = EquipmentArea.Lithography,
    recipeId = "LITHO-28-001",
    expectedDuration = 45.minutes,
    maxRetries = 3
  )

  // ============================================================================
  // Wafer Classification
  // ============================================================================

  "DynamicFlowAssembler.classifyWafer" should {

    "classify CD within spec as PASS" in {
      DynamicFlowAssembler.classifyWafer(32.0, defaultSpec, reworkCount = 0) shouldBe a[PassDisposition]
    }

    "classify CD at lower spec boundary as PASS" in {
      DynamicFlowAssembler.classifyWafer(28.0, defaultSpec, reworkCount = 0) shouldBe a[PassDisposition]
    }

    "classify CD at upper spec boundary as PASS" in {
      DynamicFlowAssembler.classifyWafer(34.0, defaultSpec, reworkCount = 0) shouldBe a[PassDisposition]
    }

    "classify first borderline (no rework) as conditional PASS" in {
      val r = DynamicFlowAssembler.classifyWafer(35.5, defaultSpec, reworkCount = 0)
      r shouldBe a[PassDisposition]
    }

    "classify borderline with rework history as REWORK" in {
      val r = DynamicFlowAssembler.classifyWafer(35.0, defaultSpec, reworkCount = 1)
      r shouldBe a[ReworkDisposition]
      r.asInstanceOf[ReworkDisposition].attempt shouldBe 2
      r.asInstanceOf[ReworkDisposition].maxRetries shouldBe 3
    }

    "classify borderline at max retries as SCRAP" in {
      val r = DynamicFlowAssembler.classifyWafer(35.5, defaultSpec, reworkCount = 3)
      r shouldBe a[ScrapDisposition]
    }

    "classify far out of spec (> upperSpec + 8nm) as SCRAP" in {
      val r = DynamicFlowAssembler.classifyWafer(43.0, defaultSpec, reworkCount = 0)
      r shouldBe a[ScrapDisposition]
    }

    "classify below lower spec as REWORK on first attempt" in {
      val r = DynamicFlowAssembler.classifyWafer(27.0, defaultSpec, reworkCount = 0)
      r shouldBe a[ReworkDisposition]
      r.asInstanceOf[ReworkDisposition].attempt shouldBe 1
    }

    "classify below lower spec at max retries as SCRAP" in {
      val r = DynamicFlowAssembler.classifyWafer(27.0, defaultSpec, reworkCount = 3)
      r shouldBe a[ScrapDisposition]
    }

    "classify CD just above borderline window as REWORK (not scrap)" in {
      // upperSpec=34, borderlineWindow=2 → 34+2=36, 36 < 34+8=42 → still FAIL zone
      val r = DynamicFlowAssembler.classifyWafer(38.0, defaultSpec, reworkCount = 0)
      r shouldBe a[ReworkDisposition]
    }
  }

  // ============================================================================
  // Step Decision Aggregation
  // ============================================================================

  "DynamicFlowAssembler.decideNextStep" should {

    "return AdvanceToNextStep when all wafers PASS" in {
      val disps = Map(
        "W1" -> PassDisposition("W1"),
        "W2" -> PassDisposition("W2"),
        "W3" -> PassDisposition("W3")
      )
      DynamicFlowAssembler.decideNextStep(disps, exampleStep) shouldBe AdvanceToNextStep
    }

    "return AdvanceToNextStep for empty dispositions" in {
      DynamicFlowAssembler.decideNextStep(Map.empty, exampleStep) shouldBe AdvanceToNextStep
    }

    "return RetryCurrentStep when some wafers need REWORK (within maxRetries)" in {
      val disps = Map(
        "W1" -> PassDisposition("W1"),
        "W2" -> ReworkDisposition("W2", attempt = 1, maxRetries = 3),
        "W3" -> PassDisposition("W3")
      )
      val d = DynamicFlowAssembler.decideNextStep(disps, exampleStep)
      d shouldBe a[RetryCurrentStep]
      d.asInstanceOf[RetryCurrentStep].waferIds should contain("W2")
    }

    "return ScrapWafersDecision when any wafer is SCRAP (takes priority over REWORK)" in {
      val disps = Map(
        "W1" -> PassDisposition("W1"),
        "W2" -> ScrapDisposition("W2", "CD out of spec"),
        "W3" -> ReworkDisposition("W3", attempt = 1, maxRetries = 3)
      )
      val d = DynamicFlowAssembler.decideNextStep(disps, exampleStep)
      d shouldBe a[ScrapWafersDecision]
    }

    "return SplitAndRework when rework attempt exceeds step maxRetries" in {
      val disps = Map(
        "W1" -> ReworkDisposition("W1", attempt = 5, maxRetries = 3)
      )
      val d = DynamicFlowAssembler.decideNextStep(disps, exampleStep.copy(maxRetries = 3))
      d shouldBe a[SplitAndRework]
      d.asInstanceOf[SplitAndRework].waferIds should contain("W1")
    }

    "return RetryCurrentStep when all reworks are within step maxRetries" in {
      val disps = Map(
        "W1" -> ReworkDisposition("W1", attempt = 2, maxRetries = 5),
        "W2" -> ReworkDisposition("W2", attempt = 1, maxRetries = 5)
      )
      val d = DynamicFlowAssembler.decideNextStep(disps, exampleStep.copy(maxRetries = 5))
      d shouldBe a[RetryCurrentStep]
      d.asInstanceOf[RetryCurrentStep].waferIds should (contain("W1") and contain("W2"))
    }
  }

  // ============================================================================
  // Fallback Area Selection
  // ============================================================================

  "DynamicFlowAssembler.selectFallbackArea" should {

    "select first available fallback area" in {
      val step = exampleStep.copy(fallbackAreas = List(EquipmentArea.Etch, EquipmentArea.Implant))
      val result = DynamicFlowAssembler.selectFallbackArea(step, Set("LITHO"))
      result shouldBe Some(EquipmentArea.Etch)
    }

    "skip unavailable fallback areas" in {
      val step = exampleStep.copy(fallbackAreas = List(EquipmentArea.Etch, EquipmentArea.Implant))
      val result = DynamicFlowAssembler.selectFallbackArea(step, Set("ETCH"))
      result shouldBe Some(EquipmentArea.Implant)
    }

    "return None when all fallback areas are unavailable" in {
      val step = exampleStep.copy(fallbackAreas = List(EquipmentArea.Etch, EquipmentArea.Implant))
      val result = DynamicFlowAssembler.selectFallbackArea(step, Set("ETCH", "IMPL"))
      result shouldBe None
    }

    "return None when fallback list is empty" in {
      val step = exampleStep.copy(fallbackAreas = Nil)
      val result = DynamicFlowAssembler.selectFallbackArea(step, Set("LITHO"))
      result shouldBe None
    }
  }

  // ============================================================================
  // Reentry Index Calculation
  // ============================================================================

  "DynamicFlowAssembler.calculateReentryIndex" should {

    "return 0 for first visit" in {
      DynamicFlowAssembler.calculateReentryIndex("LITHO", Seq("CLEAN", "DIFF")) shouldBe 0
    }

    "return 1 for second visit" in {
      DynamicFlowAssembler.calculateReentryIndex("LITHO", Seq("CLEAN", "DIFF", "LITHO", "ETCH")) shouldBe 1
    }

    "return 2 for third visit" in {
      DynamicFlowAssembler.calculateReentryIndex("LITHO", Seq("CLEAN", "LITHO", "ETCH", "LITHO", "DEP")) shouldBe 2
    }

    "return 0 for empty history" in {
      DynamicFlowAssembler.calculateReentryIndex("LITHO", Seq.empty) shouldBe 0
    }

    "return 0 when area never visited" in {
      DynamicFlowAssembler.calculateReentryIndex("CMP", Seq("CLEAN", "DIFF", "LITHO")) shouldBe 0
    }
  }
}
