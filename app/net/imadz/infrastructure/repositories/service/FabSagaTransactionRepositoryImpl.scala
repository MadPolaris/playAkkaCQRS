package net.imadz.infrastructure.repositories.service

import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.actor.typed.{ActorSystem, Scheduler}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import net.imadz.application.aggregates.repository.{LotRepository, WaferRepository}
import net.imadz.application.services.transactor.{FabSagaProtocol, FabSagaTransactionRepository}
import net.imadz.application.services.transactor.FabSagaTransactor
import net.imadz.common.CommonTypes.Id
import play.api.Application

import javax.inject.Inject
import scala.concurrent.ExecutionContext

class FabSagaTransactionRepositoryImpl @Inject()(sharding: ClusterSharding, app: Application,
                                                  lotRepository: LotRepository, waferRepository: WaferRepository)
  extends FabSagaTransactionRepository {

  val system: ActorSystem[Nothing] = app.actorSystem.toTyped
  implicit val ec: ExecutionContext = system.executionContext
  implicit val scheduler: Scheduler = system.scheduler

  override def findTransactionById(transactionId: Id): EntityRef[FabSagaProtocol.FabSagaCommand] = {
    sharding.entityRefFor(FabSagaTransactor.entityTypeKey, transactionId.toString)
  }
}
