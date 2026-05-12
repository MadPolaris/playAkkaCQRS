package net.imadz.application.projection

import akka.actor.typed.ActorSystem
import akka.contrib.persistence.mongodb.MongoReadJournal
import akka.persistence.query.Offset
import akka.projection.ProjectionId
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.jdbc.scaladsl.JdbcProjection
import akka.projection.scaladsl.{ExactlyOnceProjection, SourceProvider}
import net.imadz.application.aggregates.process.FabProcessAggregate
import net.imadz.common.application.projection.ScalikeJdbcSession
import net.imadz.infrastructure.proto.process.ProcessEventPO

object FabProcessProjection {

  val projectionName = "FabProcess"

  def createProjection(system: ActorSystem[_], index: Int): ExactlyOnceProjection[Offset, EventEnvelope[ProcessEventPO.Event]] = {
    val tag = FabProcessAggregate.tags(index)

    val sourceProvider: SourceProvider[Offset, EventEnvelope[ProcessEventPO.Event]] = EventSourcedProvider
      .eventsByTag(system = system,
        readJournalPluginId = MongoReadJournal.Identifier,
        tag = tag)

    JdbcProjection.exactlyOnce(
      projectionId = ProjectionId(projectionName, tag),
      sourceProvider = sourceProvider,
      sessionFactory = () => new ScalikeJdbcSession(),
      handler = () => new FabProcessProjectionHandler(system)
    )(system)
  }
}
