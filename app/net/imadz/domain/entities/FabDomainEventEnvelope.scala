package net.imadz.domain.entities

import net.imadz.common.CborSerializable

/**
 * Generic envelope for domain events published to EventStream by projections.
 * Used by FabDemoEventBridge to bridge Lot/Wafer/Saga domain events to WebSocket.
 */
case class FabDomainEventEnvelope(
  aggregateType: String,
  aggregateId: String,
  event: Any
) extends CborSerializable
