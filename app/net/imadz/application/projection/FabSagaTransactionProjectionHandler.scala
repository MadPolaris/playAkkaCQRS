package net.imadz.application.projection

import akka.actor.typed.ActorSystem
import akka.actor.typed.eventstream.EventStream
import akka.projection.eventsourced.EventEnvelope
import akka.projection.jdbc.scaladsl.JdbcHandler
import net.imadz.common.application.projection.ScalikeJdbcSession
import net.imadz.domain.entities.FabDomainEventEnvelope
import net.imadz.domain.entities.FabSagaTransactionEntity.FabSagaTransactionEvent
import org.slf4j.LoggerFactory

class FabSagaTransactionProjectionHandler(system: ActorSystem[_])
  extends JdbcHandler[EventEnvelope[FabSagaTransactionEvent], ScalikeJdbcSession] {

  private val logger = LoggerFactory.getLogger(getClass)

  override def process(session: ScalikeJdbcSession, envelope: EventEnvelope[FabSagaTransactionEvent]): Unit = {
    val sagaId = envelope.persistenceId.split("\\|", 2).lastOption.getOrElse(envelope.persistenceId)
    logger.debug(s"[FabSagaTransactionProjection] Publishing domain event: ${envelope.event.getClass.getSimpleName} for saga=$sagaId")
    system.eventStream ! EventStream.Publish(FabDomainEventEnvelope("FabSagaTransaction", sagaId, envelope.event))
  }
}
