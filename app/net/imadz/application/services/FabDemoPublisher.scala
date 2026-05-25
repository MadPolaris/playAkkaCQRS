package net.imadz.application.services

import net.imadz.domain.events.FabSimulationEvent

/**
 * Shared mutable publisher reference so recovery events (RecoveryEvent, GlobalStatusChanged)
 * from [[net.imadz.application.chain.FabPipelineExecutionActor.receiveSignal]] can reach
 * the WebSocket without going through the journal.
 *
 * @demo Recovery UX affordance only — production systems don't need explicit Recovering/Recovered signals.
 */
object FabDemoPublisher {
  @volatile var systemPublisher: FabSimulationEvent => Unit = _ => ()
}
