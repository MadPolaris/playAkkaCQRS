package net.imadz.fab.projection

import akka.actor.typed.ActorSystem
import akka.contrib.persistence.mongodb.MongoReadJournal
import akka.persistence.query.Offset
import akka.projection.ProjectionId
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.jdbc.scaladsl.JdbcProjection
import akka.projection.scaladsl.{ExactlyOnceProjection, SourceProvider}
import net.imadz.common.application.projection.{ProjectionSourceHelpers, ScalikeJdbcSession}
import net.imadz.fab.events.FabSimulationEvent

import scala.concurrent.ExecutionContext

object FabDemoViewProjection {

  val projectionName = "FabDemoView"
  val Tag = "fab-view"

  def createProjection(
    system: ActorSystem[_],
    publishToUI: FabSimulationEvent => Unit
  ): ExactlyOnceProjection[Offset, EventEnvelope[Any]] = {
    implicit val ec: ExecutionContext = system.executionContext

    val rawProvider: SourceProvider[Offset, EventEnvelope[Any]] = EventSourcedProvider
      .eventsByTag[Any](
        system = system,
        readJournalPluginId = MongoReadJournal.Identifier,
        tag = Tag
      )

    JdbcProjection.exactlyOnce(
      projectionId = ProjectionId(projectionName, Tag),
      sourceProvider = ProjectionSourceHelpers.withIdleTimeout(rawProvider),
      sessionFactory = () => new ScalikeJdbcSession(),
      handler = () => new FabDemoViewHandler(publishToUI)
    )(system)
  }
}
