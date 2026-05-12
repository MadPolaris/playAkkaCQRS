package net.imadz.infrastructure.bootstrap

import akka.actor.ExtendedActorSystem
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import net.imadz.application.aggregates.repository.{CreditBalanceRepository, LotRepository, WaferRepository}
import net.imadz.application.projection.repository.MonthlyIncomeAndExpenseSummaryRepository
import net.imadz.application.services.{FabSagaService, MoneyTransferService}
import net.imadz.application.services.transactor.MoneyTransferContext
import net.imadz.common.serialization.SerializationExtension
import net.imadz.infrastructure.persistence.strategies.TransactionSerializationStrategies

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

/**
 * ApplicationBootstrap: 系统的总启动入口。
 * 负责在应用启动时，一次性初始化所有的 Aggregate、Saga 和 Projection。
 */
@Singleton
class ApplicationBootstrap @Inject()(
                                      // Play 默认注入的是 Classic ActorSystem，我们需要转换
                                      classicSystem: akka.actor.ActorSystem,
                                      sharding: ClusterSharding,
                                      // 注入各个 Bootstrap 所需的 Repository
                                      creditBalanceRepository: CreditBalanceRepository,
                                      monthlyRepository: MonthlyIncomeAndExpenseSummaryRepository,
                                      lotRepository: LotRepository,
                                      waferRepository: WaferRepository
                                    ) extends CreditBalanceBootstrap
  with TransactionBootstrap
  with SagaTransactionCoordinatorBootstrap
  with MonthlyIncomeAndExpenseBootstrap
  with FabBootstrap {

  // 转换为 Typed ActorSystem
  private implicit val system: ActorSystem[Nothing] = classicSystem.toTyped
  private implicit val exec: ExecutionContext = system.executionContext
  val serializationExtension: SerializationExtension = SerializationExtension(classicSystem.asInstanceOf[ExtendedActorSystem])
  // 2. 注册您的业务策略 (这一步就是"挂号")
  serializationExtension.registerStrategy(TransactionSerializationStrategies.FromAccountStrategy(creditBalanceRepository))
  serializationExtension.registerStrategy(TransactionSerializationStrategies.ToAccountStrategy(creditBalanceRepository))
  serializationExtension.registerStrategy(net.imadz.application.services.transactor.ShowcaseStrategy)

  // 注册 Fab Saga 序列化策略
  registerFabSerializationStrategies(serializationExtension, lotRepository, waferRepository)

  serializationExtension.validateStrategies()


  // --- 1. 初始化标准聚合根 (CreditBalance) ---
  initCreditBalanceAggregate(sharding)

  // --- 2. 初始化 Saga 引擎 (Coordinator) for Banking ---
  initSagaTransactionCoordinatorAggregate[MoneyTransferContext](
    sharding = sharding,
    context = MoneyTransferContext(creditBalanceRepository),
    entityTypeKey = MoneyTransferService.moneyTransferCoordinatorKey,
    system = classicSystem.asInstanceOf[ExtendedActorSystem])

  // --- 3. 初始化 Saga 业务聚合根 (MoneyTransferTransaction) ---
  initTransactionAggregate(
    coordinatorEntityKey = MoneyTransferService.moneyTransferCoordinatorKey,
    sharding = sharding,
    repository = creditBalanceRepository)

  // --- 4. 初始化 Fab Lot / Wafer / Process 聚合根 ---
  initLotAggregate(sharding)
  initWaferAggregate(sharding)
  initFabProcessAggregate(sharding)

  // --- 5. 初始化 Fab Saga Coordinator + Transactor ---
  initFabSagaCoordinator(
    sharding = sharding,
    lotRepository = lotRepository,
    waferRepository = waferRepository,
    system = classicSystem.asInstanceOf[ExtendedActorSystem],
    sagaTransactionCoordinatorBootstrap = this
  )
  initFabSagaTransactor(
    coordinatorEntityKey = FabSagaService.fabSagaCoordinatorKey,
    sharding = sharding,
    lotRepository = lotRepository,
    waferRepository = waferRepository
  )

  // --- 6. 初始化投影 (Projection) ---
  initMonthlySummaryProjection(system, sharding, monthlyRepository)
  initFabProcessProjection(system)
  initFabLotProjection(system)
  initFabWaferProjection(system)
  initFabSagaTransactionProjection(system)

  println("🚀 [ApplicationBootstrap] All CQRS components initialized successfully.")
}