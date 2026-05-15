package net.imadz.fab.simulation

import net.imadz.fab.protocol.{EquipmentResult, LithoExposureResult}

/**
 * Generic equipment simulator for non-specialized process steps
 * (Clean, Diffusion, Etch, Implant, Deposition, CMP, Drying, Logistics).
 *
 * Processes any recipe and returns an empty LithoExposureResult.
 * The FabFlowEngine.measureAndClassify stage handles CD measurement
 * separately via CdSemSimulator.
 */
class GenericEquipmentSimulator extends EquipmentSimulator {

  override protected def generateResult(
    state: SimState, job: Job, config: EquipmentConfig
  ): EquipmentResult = {
    LithoExposureResult(job.jobId, job.recipeId, Map.empty)
  }
}
