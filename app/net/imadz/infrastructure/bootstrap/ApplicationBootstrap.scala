package net.imadz.infrastructure.bootstrap

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.application.projection.repository.MonthlyIncomeAndExpenseSummaryRepository
import net.imadz.application.services.transactor.{MoneyTransferContext, MoneyTransferSagaDefinition, ShowcaseSagaDefinition}

import javax.inject.{Inject, Singleton}

/**
  * ApplicationBootstrap: 系统的总启动入口。
  * 负责在应用启动时，一次性初始化所有的 Aggregate、Saga 和 Projection。
  *
  * saga_v3: participants are rebuilt from registered SagaDefinitions — the participant
  * serializer strategy stack is gone entirely.
  */
@Singleton
class ApplicationBootstrap @Inject()(
                                       classicSystem: akka.actor.ActorSystem,
                                       sharding: ClusterSharding,
                                       creditBalanceRepository: CreditBalanceRepository,
                                       monthlyRepository: MonthlyIncomeAndExpenseSummaryRepository
                                     ) extends CreditBalanceBootstrap
  with SagaEngineBootstrap
  with SagaBusinessEventProjectionBootstrap
  with MonthlyIncomeAndExpenseBootstrap {

  private implicit val system: ActorSystem[Nothing] = classicSystem.toTyped
  private implicit val ec: scala.concurrent.ExecutionContext = system.executionContext
  private implicit val scheduler: akka.actor.typed.Scheduler = system.scheduler

  // --- 0. Register saga definitions (per node, before sharding recovers any entity) ---
  MoneyTransferSagaDefinition.register
  ShowcaseSagaDefinition.register

  // --- 1. 初始化标准聚合根 (CreditBalance) ---
  initCreditBalanceAggregate(sharding)

  // --- 2. 初始化 Saga 引擎 (single coordinator sharding shared by all definitions) ---
  initSagaEngine[MoneyTransferContext](sharding, MoneyTransferContext(creditBalanceRepository), system)

  // --- 3. 初始化业务事件投影 (onResult) ---
  initSagaBusinessEventProjection(system, sharding)

  // --- 4. 初始化月度收支投影 ---
  initMonthlySummaryProjection(system, sharding, monthlyRepository)

  println("🚀 [ApplicationBootstrap] All CQRS components initialized successfully.")
}
