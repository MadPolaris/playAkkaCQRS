package net.imadz.application.chain

import net.imadz.application.chain.FabExecutionModel.FabDemoState
import net.imadz.domain.events.FabSimulationEvent

import scala.concurrent.Future

/**
 * Pure engine library — runs pipeline steps against POR routing.
 *
 * Formerly a sharded EventSourcedBehavior (M2.5+ ChainExecutionActor lineage).
 * Now a pure function: takes POR + Lot refs + SagaTxFn + publisher, produces FabDemoState.
 */
object FabChainExecutor {

  /**
   * Pipeline runner closure — creates Lot/Wafer entities, builds context, runs pipeline.
   *
   * MUST be idempotent — called both on initial WorkOrder start and on recovery replay.
   * Idempotency is achieved via deterministic UUIDs derived from workOrderId.
   */
  type PipelineStarter = (String, String, Seq[String], FabSimulationEvent => Unit) => Future[FabDemoState]
}
