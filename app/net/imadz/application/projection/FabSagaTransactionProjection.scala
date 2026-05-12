package net.imadz.application.projection

import akka.actor.typed.ActorSystem
import akka.contrib.persistence.mongodb.MongoReadJournal
import akka.persistence.query.Offset
import akka.projection.ProjectionId
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.jdbc.scaladsl.JdbcProjection
import akka.projection.scaladsl.{ExactlyOnceProjection, SourceProvider}
import net.imadz.common.application.projection.ScalikeJdbcSession
import net.imadz.domain.entities.FabSagaTransactionEntity.FabSagaTransactionEvent

object FabSagaTransactionProjection {

  val projectionName = "FabSagaTransaction"
  val tags: Vector[String] = Vector.tabulate(5)(i => s"fabsaga-$i")

  def createProjection(system: ActorSystem[_], index: Int): ExactlyOnceProjection[Offset, EventEnvelope[FabSagaTransactionEvent]] = {
    val tag = tags(index)

    val sourceProvider: SourceProvider[Offset, EventEnvelope[FabSagaTransactionEvent]] = EventSourcedProvider
      .eventsByTag(system = system,
        readJournalPluginId = MongoReadJournal.Identifier,
        tag = tag)

    JdbcProjection.exactlyOnce(
      projectionId = ProjectionId(projectionName, tag),
      sourceProvider = sourceProvider,
      sessionFactory = () => new ScalikeJdbcSession(),
      handler = () => new FabSagaTransactionProjectionHandler(system)
    )(system)
  }
}
