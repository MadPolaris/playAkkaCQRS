package net.imadz.application.aggregates.repository

import akka.cluster.sharding.typed.scaladsl.EntityRef
import com.google.inject.ImplementedBy
import net.imadz.application.aggregates.WaferProtocol.WaferCommand
import net.imadz.common.CommonTypes.Id
import net.imadz.infrastructure.repositories.aggregate.WaferRepositoryImpl

@ImplementedBy(classOf[WaferRepositoryImpl])
trait WaferRepository {
  def findWaferById(waferId: Id): EntityRef[WaferCommand]
}
