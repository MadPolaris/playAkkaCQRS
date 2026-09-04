package net.imadz.application.projection

import akka.actor.typed.ActorSystem
import akka.contrib.persistence.mongodb.MongoReadJournal
import akka.persistence.query.Offset
import akka.projection.ProjectionId
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.jdbc.scaladsl.JdbcProjection
import akka.projection.scaladsl.{ExactlyOnceProjection, SourceProvider}
import net.imadz.application.services.transactor.FabSagaTransactor
import net.imadz.common.application.projection.{ProjectionSourceHelpers, ScalikeJdbcSession}
import net.imadz.domain.entities.FabSagaTransactionEntity.FabSagaTransactionEvent

import scala.concurrent.ExecutionContext

object FabSagaTransactionProjection {

  val projectionName = "FabSagaTransaction"
  val tags: Vector[String] = FabSagaTransactor.tags

  def createProjection(system: ActorSystem[_], index: Int): ExactlyOnceProjection[Offset, EventEnvelope[FabSagaTransactionEvent]] = {
    implicit val ec: ExecutionContext = system.executionContext
    val tag = tags(index)

    val rawProvider: SourceProvider[Offset, EventEnvelope[FabSagaTransactionEvent]] = EventSourcedProvider
      .eventsByTag(system = system,
        readJournalPluginId = MongoReadJournal.Identifier,
        tag = tag)

    JdbcProjection.exactlyOnce(
      projectionId = ProjectionId(projectionName, tag),
      sourceProvider = ProjectionSourceHelpers.withIdleTimeout(rawProvider),
      sessionFactory = () => new ScalikeJdbcSession(),
      handler = () => new FabSagaTransactionProjectionHandler(system)
    )(system)
  }
}
