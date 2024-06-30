package net.imadz.application.projection

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.contrib.persistence.mongodb.MongoReadJournal
import akka.persistence.query.Offset
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.jdbc.scaladsl.JdbcProjection
import akka.projection.scaladsl.{ExactlyOnceProjection, SourceProvider}
import akka.projection.{ProjectionId, eventsourced}
import net.imadz.application.aggregates.CreditBalanceAggregate
import net.imadz.application.projection.repository.MonthlyIncomeAndExpenseSummaryRepository
import net.imadz.domain.entities.CreditBalanceEntity.CreditBalanceEvent
import net.imadz.infrastructure.proto.credits.CreditBalanceEventPO

object MonthlyIncomeAndExpenseSummaryProjection {

  val projectionName = "MonthlyIncomeAndExpenseSummary"


  def createProjection(system: ActorSystem[_], sharding: ClusterSharding, index: Int, repository: MonthlyIncomeAndExpenseSummaryRepository): ExactlyOnceProjection[Offset, EventEnvelope[CreditBalanceEventPO.Event]] = {
    val sourceProvider: SourceProvider[Offset, eventsourced.EventEnvelope[CreditBalanceEventPO.Event]] = EventSourcedProvider
      .eventsByTag(system = system,
        readJournalPluginId = MongoReadJournal.Identifier,
        tag = CreditBalanceAggregate.tags(index))

    JdbcProjection.exactlyOnce(
      projectionId = ProjectionId(projectionName, CreditBalanceAggregate.tags(index)),
      sourceProvider = sourceProvider,
      sessionFactory = () => new ScalikeJdbcSession(),
      handler = () => MonthlyIncomeAndExpenseSummaryProjectionHandler(sharding, repository)
    )(system)
  }
}
