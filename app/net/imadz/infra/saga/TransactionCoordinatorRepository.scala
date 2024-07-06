package net.imadz.infra.saga

import akka.cluster.sharding.typed.scaladsl.EntityRef
import com.google.inject.ImplementedBy
import net.imadz.common.CommonTypes.Id
import net.imadz.infrastructure.repositories.aggregate.SagaTransactionCoordinatorRepositoryImpl

@ImplementedBy(classOf[SagaTransactionCoordinatorRepositoryImpl])
trait TransactionCoordinatorRepository {

  def findSagaTransactionCoordinator(sagaTransactionId: Id): EntityRef[SagaTransactionCoordinator.Command]
}
