package net.imadz.application.services.transactor

import akka.cluster.sharding.typed.scaladsl.EntityRef
import com.google.inject.ImplementedBy
import net.imadz.application.services.transactor.MoneyTransferProtocol.MoneyTransferTransactionCommand
import net.imadz.common.CommonTypes.Id
import net.imadz.infrastructure.repositories.service.MoneyTransferTransactionRepositoryImpl

@ImplementedBy(classOf[MoneyTransferTransactionRepositoryImpl])
trait MoneyTransferTransactionRepository {

  // [修改] 返回值从 ActorRef 改为 EntityRef
  def findTransactionById(transaction: Id): EntityRef[MoneyTransferTransactionCommand]
}
