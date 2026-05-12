package net.imadz.application.projection

import akka.actor.typed.ActorSystem
import akka.actor.typed.eventstream.EventStream
import akka.projection.eventsourced.EventEnvelope
import akka.projection.jdbc.scaladsl.JdbcHandler
import net.imadz.common.application.projection.ScalikeJdbcSession
import net.imadz.domain.entities.FabDomainEventEnvelope
import net.imadz.infrastructure.persistence.WaferEventAdapter
import net.imadz.infrastructure.proto.wafer.WaferEventPO
import org.slf4j.LoggerFactory

class FabWaferProjectionHandler(system: ActorSystem[_])
  extends JdbcHandler[EventEnvelope[WaferEventPO.Event], ScalikeJdbcSession] {

  private val logger = LoggerFactory.getLogger(getClass)
  private val adapter = new WaferEventAdapter

  override def process(session: ScalikeJdbcSession, envelope: EventEnvelope[WaferEventPO.Event]): Unit = {
    val waferId = envelope.persistenceId.split("\\|", 2).lastOption.getOrElse(envelope.persistenceId)
    adapter.fromJournal(envelope.event, "").events.foreach { event =>
      logger.debug(s"[FabWaferProjection] Publishing domain event: ${event.getClass.getSimpleName} for wafer=$waferId")
      system.eventStream ! EventStream.Publish(FabDomainEventEnvelope("Wafer", waferId, event))
    }
  }
}
