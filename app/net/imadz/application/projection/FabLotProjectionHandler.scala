package net.imadz.application.projection

import akka.actor.typed.ActorSystem
import akka.actor.typed.eventstream.EventStream
import akka.projection.eventsourced.EventEnvelope
import akka.projection.jdbc.scaladsl.JdbcHandler
import net.imadz.common.application.projection.ScalikeJdbcSession
import net.imadz.domain.entities.FabDomainEventEnvelope
import net.imadz.infrastructure.persistence.LotEventAdapter
import net.imadz.infrastructure.proto.lot.LotEventPO
import org.slf4j.LoggerFactory

class FabLotProjectionHandler(system: ActorSystem[_])
  extends JdbcHandler[EventEnvelope[LotEventPO.Event], ScalikeJdbcSession] {

  private val logger = LoggerFactory.getLogger(getClass)
  private val adapter = new LotEventAdapter

  override def process(session: ScalikeJdbcSession, envelope: EventEnvelope[LotEventPO.Event]): Unit = {
    val lotId = envelope.persistenceId.split("\\|", 2).lastOption.getOrElse(envelope.persistenceId)
    adapter.fromJournal(envelope.event, "").events.foreach { event =>
      logger.debug(s"[FabLotProjection] Publishing domain event: ${event.getClass.getSimpleName} for lot=$lotId")
      system.eventStream ! EventStream.Publish(FabDomainEventEnvelope("Lot", lotId, event))
    }
  }
}
