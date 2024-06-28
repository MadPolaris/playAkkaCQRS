package net.imadz.infrastructure.bootstrap

import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import net.imadz.common.CommonTypes.Id
import net.imadz.common.application.saga.TransactionCoordinator
import net.imadz.common.application.saga.TransactionCoordinator.entityTypeKey

case class TransactionCoordinatorRepositoryImpl(sharding: ClusterSharding) extends TransactionCoordinatorRepository {

  override def findTransactionCoordinatorByTrxId(id: Id): EntityRef[TransactionCoordinator.SagaCommand] =
    sharding.entityRefFor(entityTypeKey, id.toString)

}
