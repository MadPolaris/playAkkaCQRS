package net.imadz.application.projection

import akka.actor.typed.ActorSystem
import akka.actor.typed.eventstream.EventStream
import akka.projection.eventsourced.EventEnvelope
import akka.projection.jdbc.scaladsl.JdbcHandler
import net.imadz.common.application.projection.ScalikeJdbcSession
import net.imadz.domain.entities.FabDomainEventEnvelope
import net.imadz.infrastructure.persistence.ProcessEventAdapter
import net.imadz.infrastructure.proto.process.ProcessEventPO
import org.slf4j.LoggerFactory

class FabProcessProjectionHandler(system: ActorSystem[_])
  extends JdbcHandler[EventEnvelope[ProcessEventPO.Event], ScalikeJdbcSession] {

  private val logger = LoggerFactory.getLogger(getClass)
  private val adapter = new ProcessEventAdapter

  override def process(session: ScalikeJdbcSession, envelope: EventEnvelope[ProcessEventPO.Event]): Unit = {
    val processId = envelope.persistenceId.split("\\|", 2).lastOption.getOrElse(envelope.persistenceId)
    val events = try {
      adapter.fromJournal(envelope.event, "").events
    } catch {
      case ex: Exception =>
        logger.error(
          s"[FabProcessProjection] FAILED deserializing event at sn=${envelope.sequenceNr} " +
          s"pid=${envelope.persistenceId} offset=${envelope.offset}", ex)
        throw ex
    }
    events.foreach { event =>
      try {
        logger.info(s"[FabProcessProjection] sn=${envelope.sequenceNr} ${event.getClass.getSimpleName} process=$processId")
        system.eventStream ! EventStream.Publish(FabDomainEventEnvelope("FabProcess", processId, event))
      } catch {
        case ex: Exception =>
          logger.error(
            s"[FabProcessProjection] FAILED publishing ${event.getClass.getSimpleName} " +
            s"sn=${envelope.sequenceNr} process=$processId", ex)
          throw ex
      }
    }
  }
}
