package net.imadz.application.projection

import akka.actor.typed.ActorSystem
import akka.actor.typed.eventstream.EventStream
import akka.projection.eventsourced.EventEnvelope
import akka.projection.jdbc.scaladsl.JdbcHandler
import net.imadz.common.application.projection.ScalikeJdbcSession
import net.imadz.domain.entities.FabProcessEntity.ProcessEventEnvelope
import net.imadz.infrastructure.persistence.ProcessEventAdapter
import net.imadz.infrastructure.proto.process.ProcessEventPO
import org.slf4j.LoggerFactory

class FabProcessProjectionHandler(system: ActorSystem[_])
  extends JdbcHandler[EventEnvelope[ProcessEventPO.Event], ScalikeJdbcSession] {

  private val logger = LoggerFactory.getLogger(getClass)
  private val adapter = new ProcessEventAdapter

  override def process(session: ScalikeJdbcSession, envelope: EventEnvelope[ProcessEventPO.Event]): Unit = {
    val processId = envelope.persistenceId.split("\\|", 2).lastOption.getOrElse(envelope.persistenceId)
    adapter.fromJournal(envelope.event, "").events.foreach { event =>
      logger.debug(s"[FabProcessProjection] Publishing domain event: ${event.getClass.getSimpleName} for process=$processId")
      system.eventStream ! EventStream.Publish(ProcessEventEnvelope(processId, event))
    }
  }
}
