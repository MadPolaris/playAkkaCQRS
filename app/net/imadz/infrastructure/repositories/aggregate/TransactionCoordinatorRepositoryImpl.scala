package net.imadz.infrastructure.repositories.aggregate

import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import net.imadz.common.CommonTypes.Id
import net.imadz.common.application.saga.TransactionCoordinator
import net.imadz.common.application.saga.TransactionCoordinator.entityTypeKey
import net.imadz.common.application.saga.repository.TransactionCoordinatorRepository

import javax.inject.Inject

case class TransactionCoordinatorRepositoryImpl @Inject()(sharding: ClusterSharding) extends TransactionCoordinatorRepository {

  override def findTransactionCoordinatorByTrxId(id: Id): EntityRef[TransactionCoordinator.SagaCommand] =
    sharding.entityRefFor(entityTypeKey, id.toString)

}
