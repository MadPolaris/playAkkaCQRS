package net.imadz.common.application.saga.repository

import akka.cluster.sharding.typed.scaladsl.EntityRef
import com.google.inject.ImplementedBy
import net.imadz.common.CommonTypes.Id
import net.imadz.common.application.saga.TransactionCoordinator
import net.imadz.infrastructure.repositories.aggregate.TransactionCoordinatorRepositoryImpl

@ImplementedBy(classOf[TransactionCoordinatorRepositoryImpl])
trait TransactionCoordinatorRepository {

  def findTransactionCoordinatorByTrxId(id: Id): EntityRef[TransactionCoordinator.SagaCommand]
}
