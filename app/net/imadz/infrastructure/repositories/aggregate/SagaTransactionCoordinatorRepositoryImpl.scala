package net.imadz.infrastructure.repositories.aggregate

import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import net.imadz.common.CommonTypes.Id
import net.imadz.infra.saga.{SagaTransactionCoordinator, TransactionCoordinatorRepository}

import javax.inject.Inject

case class SagaTransactionCoordinatorRepositoryImpl @Inject()(sharding: ClusterSharding) extends TransactionCoordinatorRepository {
  override def findSagaTransactionCoordinator(sagaTransactionId: Id): EntityRef[SagaTransactionCoordinator.Command] =
    sharding.entityRefFor(SagaTransactionCoordinator.entityTypeKey, sagaTransactionId.toString)
}
