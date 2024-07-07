package net.imadz.infra.saga.repository

import akka.cluster.sharding.typed.scaladsl.EntityRef
import com.google.inject.ImplementedBy
import net.imadz.common.CommonTypes.Id
import net.imadz.infra.saga.SagaTransactionCoordinator

@ImplementedBy(classOf[SagaTransactionCoordinatorRepositoryImpl])
trait TransactionCoordinatorRepository {

  def findSagaTransactionCoordinator(sagaTransactionId: Id): EntityRef[SagaTransactionCoordinator.Command]
}
