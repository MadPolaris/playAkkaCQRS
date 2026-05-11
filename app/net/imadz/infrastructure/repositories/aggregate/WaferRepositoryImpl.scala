package net.imadz.infrastructure.repositories.aggregate

import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import net.imadz.application.aggregates.WaferAggregate
import net.imadz.application.aggregates.WaferProtocol.WaferCommand
import net.imadz.application.aggregates.repository.WaferRepository
import net.imadz.common.CommonTypes.Id

import javax.inject.Inject

case class WaferRepositoryImpl @Inject()(sharding: ClusterSharding) extends WaferRepository {

  override def findWaferById(waferId: Id): EntityRef[WaferCommand] =
    sharding.entityRefFor(WaferAggregate.WaferEntityTypeKey, waferId.toString)
}
