package net.imadz.infrastructure.repositories.aggregate

import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import net.imadz.application.aggregates.CreditBalanceAggregate
import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.common.CommonTypes.Id

case class CreditBalanceRepositoryImpl(sharding: ClusterSharding) extends CreditBalanceRepository {

  override def findCreditBalanceByUserId(userId: Id): EntityRef[CreditBalanceAggregate.CreditBalanceCommand] =
    sharding.entityRefFor(CreditBalanceAggregate.CreditBalanceEntityTypeKey, userId.toString)
}
