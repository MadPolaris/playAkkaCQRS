package net.imadz.infrastructure.repositories.aggregate

import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import net.imadz.application.aggregates.{CreditBalanceAggregate, CreditBalanceProtocol}
import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.common.CommonTypes.Id

import javax.inject.Inject

case class CreditBalanceRepositoryImpl @Inject()(sharding: ClusterSharding) extends CreditBalanceRepository {

  override def findCreditBalanceByUserId(userId: Id): EntityRef[CreditBalanceProtocol.CreditBalanceCommand] =
    sharding.entityRefFor(CreditBalanceAggregate.CreditBalanceEntityTypeKey, userId.toString)
}
