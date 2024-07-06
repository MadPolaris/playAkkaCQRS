package net.imadz.infra.saga

import akka.cluster.sharding.typed.scaladsl.EntityRef
import net.imadz.common.CommonTypes.Id

trait TransactionCoordinatorRepository {

  def findSagaTransactionCoordinator(sagaTransactionId: Id): EntityRef[SagaTransactionCoordinator.Command]
}
