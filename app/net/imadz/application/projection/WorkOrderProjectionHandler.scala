package net.imadz.application.projection

import akka.actor.typed.ActorSystem
import akka.actor.typed.eventstream.EventStream
import akka.projection.eventsourced.EventEnvelope
import akka.projection.jdbc.scaladsl.JdbcHandler
import net.imadz.common.application.projection.ScalikeJdbcSession
import net.imadz.domain.entities.FabDomainEventEnvelope
import net.imadz.infrastructure.persistence.WorkOrderEventAdapter
import net.imadz.infrastructure.proto.work_order.WorkOrderEventPO
import org.slf4j.LoggerFactory

class WorkOrderProjectionHandler(system: ActorSystem[_])
  extends JdbcHandler[EventEnvelope[WorkOrderEventPO], ScalikeJdbcSession] {

  private val logger = LoggerFactory.getLogger(getClass)
  private val adapter = new WorkOrderEventAdapter

  override def process(session: ScalikeJdbcSession, envelope: EventEnvelope[WorkOrderEventPO]): Unit = {
    val workOrderId = envelope.persistenceId.split("\\|", 2).lastOption.getOrElse(envelope.persistenceId)
    val events = try {
      adapter.fromJournal(envelope.event, "").events
    } catch {
      case ex: Exception =>
        logger.error(
          s"[WorkOrderProjection] FAILED deserializing event at sn=${envelope.sequenceNr} " +
          s"pid=${envelope.persistenceId} offset=${envelope.offset}", ex)
        throw ex
    }
    events.foreach { event =>
      try {
        logger.info(s"[WorkOrderProjection] sn=${envelope.sequenceNr} ${event.getClass.getSimpleName} workOrder=$workOrderId")
        system.eventStream ! EventStream.Publish(FabDomainEventEnvelope("WorkOrder", workOrderId, event))
      } catch {
        case ex: Exception =>
          logger.error(
            s"[WorkOrderProjection] FAILED publishing ${event.getClass.getSimpleName} " +
            s"sn=${envelope.sequenceNr} workOrder=$workOrderId", ex)
          throw ex
      }
    }
  }
}
