package net.imadz.application.services.transactor

import akka.cluster.sharding.typed.scaladsl.EntityRef
import com.google.inject.ImplementedBy
import net.imadz.application.services.transactor.FabSagaProtocol.FabSagaCommand
import net.imadz.common.CommonTypes.Id
import net.imadz.infrastructure.repositories.service.FabSagaTransactionRepositoryImpl

@ImplementedBy(classOf[FabSagaTransactionRepositoryImpl])
trait FabSagaTransactionRepository {
  def findTransactionById(transactionId: Id): EntityRef[FabSagaCommand]
}
