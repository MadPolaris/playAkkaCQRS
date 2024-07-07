package net.imadz.infrastructure.repositories.aggregate

import akka.actor.typed.{ActorRef, ActorSystem, Scheduler}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import net.imadz.application.aggregates.MoneyTransferTransactionAggregate
import net.imadz.application.aggregates.MoneyTransferTransactionAggregate.MoneyTransferTransactionCommand
import net.imadz.application.aggregates.behaviors.MoneyTransferTransactionBehaviors
import net.imadz.application.aggregates.repository.{CreditBalanceRepository, MoneyTransferTransactionRepository}
import net.imadz.common.CommonTypes.Id
import net.imadz.infra.saga.SagaTransactionCoordinator
import play.api.Application

import javax.inject.Inject
import scala.concurrent.ExecutionContext

class MoneyTransferTransactionRepositoryImpl @Inject()(sharding: ClusterSharding, app: Application,
                                                       repository: CreditBalanceRepository) extends MoneyTransferTransactionRepository {
  val system: ActorSystem[Nothing] = app.actorSystem.toTyped
  implicit val ec: ExecutionContext = system.executionContext
  implicit val scheduler: Scheduler = system.scheduler

  override def findTransactionById(transactionId: Id): ActorRef[MoneyTransferTransactionAggregate.MoneyTransferTransactionCommand] = {
    val coordinator = sharding.entityRefFor(SagaTransactionCoordinator.entityTypeKey, transactionId.toString)
    system.systemActorOf(
      Behaviors.setup[MoneyTransferTransactionCommand] { context =>
        MoneyTransferTransactionBehaviors.apply(context,
          coordinator, repository)
      },
      s"moneyTransferActor-$transactionId"
    )
  }
}