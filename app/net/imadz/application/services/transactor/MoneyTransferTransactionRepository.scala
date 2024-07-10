package net.imadz.application.services.transactor

import akka.actor.typed.ActorRef
import com.google.inject.ImplementedBy
import net.imadz.common.CommonTypes.Id
import net.imadz.infrastructure.repositories.service.MoneyTransferTransactionRepositoryImpl

@ImplementedBy(classOf[MoneyTransferTransactionRepositoryImpl])
trait MoneyTransferTransactionRepository {

  def findTransactionById(transaction: Id): ActorRef[MoneyTransferSagaTransactor.MoneyTransferTransactionCommand]
}
