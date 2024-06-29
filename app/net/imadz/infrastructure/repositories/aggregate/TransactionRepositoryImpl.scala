package net.imadz.infrastructure.repositories.aggregate

import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import net.imadz.application.aggregates.MoneyTransferTransactionAggregate
import net.imadz.application.aggregates.repository.TransactionRepository
import net.imadz.common.CommonTypes.Id

import javax.inject.Inject

class TransactionRepositoryImpl @Inject()(sharding: ClusterSharding) extends TransactionRepository {

  override def findTransactionById(transaction: Id): EntityRef[MoneyTransferTransactionAggregate.MoneyTransferTransactionCommand] =
    sharding.entityRefFor(MoneyTransferTransactionAggregate.TransactionEntityTypeKey, transaction.toString)
}