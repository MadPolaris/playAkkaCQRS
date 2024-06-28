package net.imadz.application.aggregates.repository

import akka.cluster.sharding.typed.scaladsl.EntityRef
import com.google.inject.ImplementedBy
import net.imadz.application.aggregates.MoneyTransferTransactionAggregate
import net.imadz.common.CommonTypes.Id
import net.imadz.infrastructure.repositories.aggregate.TransactionRepositoryImpl

@ImplementedBy(classOf[TransactionRepositoryImpl])
trait TransactionRepository {

  def findTransactionById(transaction: Id): EntityRef[MoneyTransferTransactionAggregate.MoneyTransferTransactionCommand]
}
