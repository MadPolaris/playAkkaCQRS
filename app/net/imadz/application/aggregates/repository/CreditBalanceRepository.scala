package net.imadz.application.aggregates.repository

import akka.cluster.sharding.typed.scaladsl.EntityRef
import com.google.inject.ImplementedBy
import net.imadz.application.aggregates.CreditBalanceAggregate.CreditBalanceCommand
import net.imadz.common.CommonTypes.Id
import net.imadz.infrastructure.repositories.aggregate.CreditBalanceRepositoryImpl

@ImplementedBy(classOf[CreditBalanceRepositoryImpl])
trait CreditBalanceRepository {

  def findCreditBalanceByUserId(userId: Id): EntityRef[CreditBalanceCommand]

}
