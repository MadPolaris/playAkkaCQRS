package net.imadz.infrastructure.bootstrap

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, ShardedDaemonProcess}
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.scaladsl.{CurrentEventsByPersistenceIdQuery, ReadJournal}
import akka.projection.ProjectionBehavior
import akka.contrib.persistence.mongodb.MongoReadJournal
import net.imadz.application.projection.SagaBusinessEventProjection
import net.imadz.infra.saga.SagaTransactionCoordinator

trait SagaBusinessEventProjectionBootstrap extends SagaEngineBootstrap {

  def initSagaBusinessEventProjection(system: ActorSystem[_], sharding: ClusterSharding): Unit = {
    val readJournal = PersistenceQuery(system.classicSystem)
      .readJournalFor[ReadJournal with CurrentEventsByPersistenceIdQuery](MongoReadJournal.Identifier)

    ShardedDaemonProcess(system).init(
      name = SagaBusinessEventProjection.projectionName,
      numberOfInstances = SagaTransactionCoordinator.tags.size,
      behaviorFactory = index => ProjectionBehavior(
        SagaBusinessEventProjection.createProjection(system, index, readJournal, SagaEngineBootstrap.CoordinatorPidPrefix)),
      settings = ShardedDaemonProcessSettings(system),
      stopMessage = Some(ProjectionBehavior.Stop)
    )
  }
}
