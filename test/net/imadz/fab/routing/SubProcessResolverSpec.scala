package net.imadz.fab.routing

import net.imadz.fab.engine.SubProcessResolver
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SubProcessResolverSpec extends AnyFlatSpec with Matchers {

  "SubProcessResolver" should "expand SendAheadPilot to 12 steps (with TrackIn/TrackOut)" in {
    val ref = SubProcessRef("n1", "Pilot", SendAheadPilot, Map(
      "lithoEquipId" -> "LITHO-01", "measureEquipId" -> "CDSEM-01",
      "pilotRecipeId" -> "LITHO-28-001"
    ))
    val steps = SubProcessResolver.expand(ref)
    steps should have size 12
    steps.head shouldBe "LoadFoup"
    steps should contain ("Transport:STOCKER->LITHO")
    steps should contain ("TrackIn:LITHO-01:LP1")
    steps should contain ("RunRecipe:LITHO-01:LITHO-28-001")
    steps should contain ("TrackOut:LITHO-01:LP1")
    steps should contain ("Measure:CDSEM-01")
    steps.last shouldBe "Classify"
  }

  it should "expand ReworkLoop to 12 steps (with TrackIn/TrackOut)" in {
    val ref = SubProcessRef("n1", "Rework", ReworkLoop, Map(
      "lithoEquipId" -> "LITHO-02", "measureEquipId" -> "CDSEM-02",
      "reworkRecipeId" -> "REWORK-LITHO-002"
    ))
    val steps = SubProcessResolver.expand(ref)
    steps should have size 12
    steps.head shouldBe "LoadFoup"
    steps should contain ("Transport:MET->LITHO")
    steps should contain ("TrackIn:LITHO-02:LP1")
    steps should contain ("RunRecipe:LITHO-02:REWORK-LITHO-002")
    steps should contain ("TrackOut:LITHO-02:LP1")
    steps should contain ("Measure:CDSEM-02")
    steps.last shouldBe "Classify"
  }

  it should "expand HoldRelease to 3 steps" in {
    val ref = SubProcessRef("n1", "Hold", HoldRelease, Map(
      "holdReason" -> "Review required", "waitMs" -> "5000"
    ))
    val steps = SubProcessResolver.expand(ref)
    steps should contain ("HoldWafers")
    steps should contain ("WaitForReview:5000")
    steps should contain ("ReleaseWafers")
  }

  it should "expand Sampling to 2 steps" in {
    val ref = SubProcessRef("n1", "Sample", Sampling, Map("measureEquipId" -> "CDSEM-01"))
    val steps = SubProcessResolver.expand(ref)
    steps should contain ("Measure:CDSEM-01")
    steps should contain ("Classify")
  }

  it should "expand ScrapDowngrade to 2 steps" in {
    val ref = SubProcessRef("n1", "Scrap", ScrapDowngrade, Map("scrapReason" -> "CD far out of spec"))
    val steps = SubProcessResolver.expand(ref)
    steps should contain ("ScrapWafers:CD far out of spec")
    steps should contain ("SealComplete")
  }

  it should "use default equipment IDs when params are missing" in {
    val ref = SubProcessRef("n1", "Rework", ReworkLoop, Map.empty)
    val steps = SubProcessResolver.expand(ref)
    steps should contain ("RunRecipe:LITHO-01:REWORK-LITHO-001")
    steps should contain ("Measure:CDSEM-01")
  }
}
