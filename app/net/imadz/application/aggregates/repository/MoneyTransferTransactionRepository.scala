package net.imadz.application.aggregates.repository

import akka.actor.typed.ActorRef
import com.google.inject.ImplementedBy
import net.imadz.application.aggregates.MoneyTransferTransactionAggregate
import net.imadz.common.CommonTypes.Id
import net.imadz.infrastructure.repositories.aggregate.MoneyTransferTransactionRepositoryImpl

@ImplementedBy(classOf[MoneyTransferTransactionRepositoryImpl])
trait MoneyTransferTransactionRepository {

  def findTransactionById(transaction: Id): ActorRef[MoneyTransferTransactionAggregate.MoneyTransferTransactionCommand]
}
