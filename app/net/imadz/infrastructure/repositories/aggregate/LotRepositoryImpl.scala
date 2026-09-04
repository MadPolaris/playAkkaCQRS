package net.imadz.infrastructure.repositories.aggregate

import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import net.imadz.application.aggregates.LotAggregate
import net.imadz.application.aggregates.LotProtocol.LotCommand
import net.imadz.application.aggregates.repository.LotRepository
import net.imadz.common.CommonTypes.Id

import javax.inject.Inject

case class LotRepositoryImpl @Inject()(sharding: ClusterSharding) extends LotRepository {

  override def findLotById(lotId: Id): EntityRef[LotCommand] =
    sharding.entityRefFor(LotAggregate.LotEntityTypeKey, lotId.toString)
}
