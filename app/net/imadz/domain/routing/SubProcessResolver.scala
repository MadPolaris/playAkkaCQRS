package net.imadz.domain.routing

import net.imadz.domain.routing._

/**
 * Resolves SubProcessRef to a concrete PipelineStage sequence at runtime
 * (Call Activity pattern). The expanded sequence is snapshotted into
 * RouteCard.steps for durable late-binding.
 *
 * Once a sub-process is resolved for a Lot, it is frozen in the Lot's Journal.
 * Future updates to the sub-process definition will only affect new Lots.
 */
object SubProcessResolver {

  /**
   * Expand a SubProcessRef into its concrete PipelineStage step identifiers.
   * These identifiers are consumed by PipelineStages and FabScenarioPipeline
   * to dispatch actual stage execution.
   *
   * @param ref     the sub-process reference (type + params)
   * @param equipId optional equipment override from params
   * @return ordered list of stage identifiers
   */
  def expand(ref: SubProcessRef, equipId: Option[String] = None): Seq[String] = {
    val params = ref.params
    val lithoEquip = equipId.getOrElse(params.getOrElse("lithoEquipId", "LITHO-01"))
    val measureEquip = params.getOrElse("measureEquipId", "CDSEM-01")
    val reworkRecipe = params.getOrElse("reworkRecipeId", "REWORK-LITHO-001")
    val pilotRecipe = params.getOrElse("pilotRecipeId", "LITHO-28-001")

    ref.subProcessType match {
      case SendAheadPilot =>
        Seq(
          "LoadFoup",
          s"Transport:STOCKER->LITHO",
          s"AtEquipment:LITHO:$lithoEquip",
          s"TrackIn:$lithoEquip:LP1",
          s"RunRecipe:$lithoEquip:$pilotRecipe",
          s"TrackOut:$lithoEquip:LP1",
          s"Transport:LITHO->MET",
          s"AtEquipment:MET:$measureEquip",
          s"TrackIn:$measureEquip:LP1",
          s"Measure:$measureEquip",
          s"TrackOut:$measureEquip:LP1",
          "Classify"
        )

      case ReworkLoop =>
        // SubLot alternate route (no LoadFoup — SubLot doesn't re-load FOUP)
        Seq(
          s"Transport:MET->LITHO",
          s"AtEquipment:LITHO:$lithoEquip",
          s"TrackIn:$lithoEquip:LP1",
          s"RunRecipe:$lithoEquip:$reworkRecipe",
          s"TrackOut:$lithoEquip:LP1",
          s"Transport:LITHO->MET",
          s"AtEquipment:MET:$measureEquip",
          s"TrackIn:$measureEquip:LP1",
          s"Measure:$measureEquip",
          s"TrackOut:$measureEquip:LP1",
          "Classify"
        )

      case HoldRelease =>
        val holdReason = params.getOrElse("holdReason", "Review required")
        val waitMs = params.getOrElse("waitMs", "5000")
        Seq(
          "HoldWafers",
          s"WaitForReview:$waitMs",
          "ReleaseWafers"
        )

      case Sampling =>
        Seq(
          s"Measure:$measureEquip",
          "Classify"
        )

      case ScrapDowngrade =>
        val reason = params.getOrElse("scrapReason", "OCAP scrap")
        Seq(
          s"ScrapWafers:$reason",
          "SealComplete"
        )
    }
  }
}
