package net.imadz.infrastructure.bootstrap

import akka.actor.typed.{ActorSystem, Behavior}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityContext}
import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.application.services.transactor.MoneyTransferSagaTransactor
import net.imadz.infra.saga.SagaTransactionCoordinator

trait TransactionBootstrap {

  // [修改] 增加了 system 参数
  def initTransactionAggregate(system: ActorSystem[_], sharding: ClusterSharding, repository: CreditBalanceRepository): Unit = {
    val behaviorFactory: EntityContext[MoneyTransferSagaTransactor.MoneyTransferTransactionCommand] => Behavior[MoneyTransferSagaTransactor.MoneyTransferTransactionCommand] = { context =>
      val transactionId = context.entityId
      val coordinator = sharding.entityRefFor(SagaTransactionCoordinator.entityTypeKey, transactionId)

      // [修改] 使用 system.executionContext
      MoneyTransferSagaTransactor.apply(transactionId, coordinator, repository)
    }

    sharding.init(Entity(MoneyTransferSagaTransactor.entityTypeKey)(behaviorFactory))
  }
}