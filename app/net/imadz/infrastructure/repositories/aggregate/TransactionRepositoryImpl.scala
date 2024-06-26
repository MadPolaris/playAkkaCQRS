package net.imadz.infrastructure.repositories.aggregate

import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import net.imadz.application.aggregates.TransactionAggregate
import net.imadz.application.aggregates.repository.TransactionRepository
import net.imadz.common.CommonTypes.Id

class TransactionRepositoryImpl(sharding: ClusterSharding) extends TransactionRepository {

  override def findTransactionById(transaction: Id): EntityRef[TransactionAggregate.TransactionCommand] =
    sharding.entityRefFor(TransactionAggregate.TransactionEntityTypeKey, transaction.toString)
}