package net.imadz.infrastructure.bootstrap

import akka.actor.ExtendedActorSystem
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import net.imadz.application.aggregates.LotAggregate.LotEntityTypeKey
import net.imadz.application.aggregates.repository.{CreditBalanceRepository, LotRepository}
import net.imadz.application.chain.FabExecutionModel.{FabDemoContext, FabDemoState}
import net.imadz.application.chain.{FabPipelineExecutionActor, FabScenarioPipeline}
import net.imadz.application.projection.repository.MonthlyIncomeAndExpenseSummaryRepository
import net.imadz.application.scenario.StandardScenarios
import net.imadz.application.services.{FabSagaService, MoneyTransferService}
import net.imadz.application.services.transactor.{FabSagaProtocol, MoneyTransferContext}
import net.imadz.common.CommonTypes.Id
import net.imadz.common.serialization.SerializationExtension
import net.imadz.fab.protocol.ActorEquipmentAdapter
import net.imadz.infrastructure.persistence.strategies.TransactionSerializationStrategies

import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

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
                                      fabSagaService: FabSagaService,
                                      ocapRuleStore: net.imadz.infrastructure.repositories.routing.OcapRuleStore
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
  registerFabSerializationStrategies(serializationExtension, lotRepository)

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

  // --- 4. 初始化 Fab Lot + WorkOrder 聚合根 ---
  initLotAggregate(sharding)
  initWorkOrderAggregate(sharding)

  // --- 5. 初始化 Fab Saga Coordinator + Transactor ---
  initFabSagaCoordinator(
    sharding = sharding,
    lotRepository = lotRepository,
    system = classicSystem.asInstanceOf[ExtendedActorSystem],
    sagaTransactionCoordinatorBootstrap = this
  )
  initFabSagaTransactor(
    coordinatorEntityKey = FabSagaService.fabSagaCoordinatorKey,
    sharding = sharding,
    lotRepository = lotRepository
  )

  // --- 6. 初始化投影 (Projection) ---
  initMonthlySummaryProjection(system, sharding, monthlyRepository)
  initWorkOrderProjection(system)
  initFabLotProjection(system)
  initWorkOrderCompletionProjection(system)
  initFabSagaTransactionProjection(system)

  // --- 7. 初始化 FabPipelineExecutionActor (用于所有 Demo/Route 执行路径) ---
  initFabPipelineExecutionActor(sharding, pipelineContextFactory, pipelineStateFactory, pipelineStageResolver)

  println("🚀 [ApplicationBootstrap] All CQRS components initialized successfully.")

  // ====================================================================
  // FabPipelineExecutionActor recovery factories
  // ====================================================================

  /** Reconstructs FabDemoContext for crash recovery. Uses deterministic UUIDs. */
  private def pipelineContextFactory(scenarioId: String, workOrderId: String): FabDemoContext = {
    val scenario = scenarioId match {
      case "send-ahead-pilot"  => StandardScenarios.sendAheadPilot
      case "scrap-downgrade"   => StandardScenarios.scrapDowngrade
      case "sampling-demo"     => StandardScenarios.samplingDemo
      case "hold-release"      => StandardScenarios.holdRelease
      case _                   => StandardScenarios.photoCell5Wafer
    }
    val waferIds = scenario.waferIds
    val waferUUIDs: Map[String, Id] = waferIds.map { wid =>
      wid -> UUID.nameUUIDFromBytes(s"$workOrderId-$wid".getBytes)
    }.toMap
    val sourceLotId: Id = UUID.nameUUIDFromBytes(s"$workOrderId-source-lot".getBytes)
    val reworkLotId: Id = UUID.nameUUIDFromBytes(s"$workOrderId-rework-lot".getBytes)
    val scrapLotId: Id  = UUID.nameUUIDFromBytes(s"$workOrderId-scrap-lot".getBytes)
    val pilotLotId: Id  = UUID.nameUUIDFromBytes(s"$workOrderId-pilot-lot".getBytes)
    val sampleLotId: Id = UUID.nameUUIDFromBytes(s"$workOrderId-sample-lot".getBytes)
    val holdLotId: Id   = UUID.nameUUIDFromBytes(s"$workOrderId-hold-lot".getBytes)

    val lotRef = sharding.entityRefFor(LotEntityTypeKey, sourceLotId.toString)
    val reworkLotRef = sharding.entityRefFor(LotEntityTypeKey, reworkLotId.toString)
    val scrapLotRef = sharding.entityRefFor(LotEntityTypeKey, scrapLotId.toString)
    val pilotLotRef = sharding.entityRefFor(LotEntityTypeKey, pilotLotId.toString)
    val sampleLotRef = sharding.entityRefFor(LotEntityTypeKey, sampleLotId.toString)
    val holdLotRef = sharding.entityRefFor(LotEntityTypeKey, holdLotId.toString)

    val sagaTxFn: (Id, Id, Set[Id], Set[String], Option[Id]) => Future[FabSagaProtocol.FabSagaConfirmation] =
      (srcId, tgtId, wids, names, existingTxId) => fabSagaService.transferWafers(srcId, tgtId, wids, names, existingTxId)

    val adapter = new ActorEquipmentAdapter()

    FabDemoContext(
      scenario = scenario,
      foupId = s"FOUP-${scenario.scenarioId}",
      lotRef = lotRef,
      reworkLotRef = reworkLotRef,
      waferUUIDs = waferUUIDs,
      sourceLotId = sourceLotId,
      reworkLotId = reworkLotId,
      adapter = adapter,
      publisher = net.imadz.application.services.FabDemoPublisher.systemPublisher,
      ignoreLotReply = system.ignoreRef,
      sagaTx = sagaTxFn,
      speedMultiplier = 1.0,
      scrapLotRef = Some(scrapLotRef),
      scrapLotId = Some(scrapLotId),
      childLotRefs = Map(
        "pilot" -> pilotLotRef, "sample" -> sampleLotRef,
        "hold" -> holdLotRef, "scrap" -> scrapLotRef,
        "rwk" -> reworkLotRef
      ),
      childLotIds = Map(
        "pilot" -> pilotLotId, "sample" -> sampleLotId,
        "hold" -> holdLotId, "scrap" -> scrapLotId,
        "rwk" -> reworkLotId
      ),
      ocapRules = ocapRuleStore.getRules
    )
  }

  private def pipelineStateFactory(workOrderId: String): FabDemoState =
    FabDemoState(wafers = Map.empty)

  private val pipelineStageResolver: String => Seq[FabScenarioPipeline.PipelineStage] = { scenarioId =>
    scenarioId match {
      case "photo-cell-5wafer" => Seq(FabScenarioPipeline.PhotoCellReworkPipeline)
      case "ocap-rework-crash" | "send-ahead-ocap" | "multi-workorder-chaos" =>
        val rules = ocapRuleStore.getRules.filter(r => r.routeId == "PHOTOCELL-5WAFER" || r.routeId.isEmpty)
        FabScenarioPipeline.m35BasicStages(rules)
      case _ => FabScenarioPipeline.resolveStages(scenarioId)
    }
  }
}