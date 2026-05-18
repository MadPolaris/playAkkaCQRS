package net.imadz.application.projection

import akka.actor.typed.ActorSystem
import akka.contrib.persistence.mongodb.MongoReadJournal
import akka.persistence.query.Offset
import akka.projection.ProjectionId
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.jdbc.scaladsl.JdbcProjection
import akka.projection.scaladsl.{ExactlyOnceProjection, SourceProvider}
import net.imadz.application.aggregates.LotAggregate
import net.imadz.common.application.projection.{ProjectionSourceHelpers, ScalikeJdbcSession}
import net.imadz.infrastructure.proto.lot.LotEventPO

import scala.concurrent.ExecutionContext

/**
 * Akka Projection that drives WorkOrder completion from Lot events.
 * Uses eventsByTag on the Lot journal tag; offset tracking guarantees
 * at-least-once delivery across restarts.
 */
object WorkOrderCompletionProjection {

  val projectionName = "WorkOrderCompletion"

  def createProjection(system: ActorSystem[_], index: Int): ExactlyOnceProjection[Offset, EventEnvelope[LotEventPO.Event]] = {
    implicit val ec: ExecutionContext = system.executionContext
    val tag = LotAggregate.tags(index)

    val rawProvider: SourceProvider[Offset, EventEnvelope[LotEventPO.Event]] = EventSourcedProvider
      .eventsByTag(system = system,
        readJournalPluginId = MongoReadJournal.Identifier,
        tag = tag)

    JdbcProjection.exactlyOnce(
      projectionId = ProjectionId(projectionName, tag),
      sourceProvider = ProjectionSourceHelpers.withIdleTimeout(rawProvider),
      sessionFactory = () => new ScalikeJdbcSession(),
      handler = () => new WorkOrderCompletionHandler(system)
    )(system)
  }
}
