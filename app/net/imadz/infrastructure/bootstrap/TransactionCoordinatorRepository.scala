package net.imadz.infrastructure.bootstrap

import akka.cluster.sharding.typed.scaladsl.EntityRef
import com.google.inject.ImplementedBy
import net.imadz.common.CommonTypes.Id
import net.imadz.common.application.saga.TransactionCoordinator

@ImplementedBy(classOf[TransactionCoordinatorRepositoryImpl])
trait TransactionCoordinatorRepository {

  def findTransactionCoordinatorByTrxId(id: Id): EntityRef[TransactionCoordinator.SagaCommand]
}
