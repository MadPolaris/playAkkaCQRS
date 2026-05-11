package net.imadz.application.aggregates.repository

import akka.cluster.sharding.typed.scaladsl.EntityRef
import com.google.inject.ImplementedBy
import net.imadz.application.aggregates.LotProtocol.LotCommand
import net.imadz.common.CommonTypes.Id
import net.imadz.infrastructure.repositories.aggregate.LotRepositoryImpl

@ImplementedBy(classOf[LotRepositoryImpl])
trait LotRepository {
  def findLotById(lotId: Id): EntityRef[LotCommand]
}
