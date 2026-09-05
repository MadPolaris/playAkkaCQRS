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
import net.imadz.application.services.transactor.{AppSagaContext, FabSagaDefinition, FabSagaProtocol, MoneyTransferSagaDefinition}
import net.imadz.common.CommonTypes.Id
import net.imadz.fab.protocol.ActorEquipmentAdapter

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
                                      fabDemoService: net.imadz.application.services.FabDemoService,
                                      bankBatchDemoService: net.imadz.m25.bank.BankBatchDemoService,
                                      ocapRuleStore: net.imadz.infrastructure.repositories.routing.OcapRuleStore
                                    ) extends CreditBalanceBootstrap
  with SagaEngineBootstrap
  with MonthlyIncomeAndExpenseBootstrap
  with FabBootstrap {

  // 转换为 Typed ActorSystem
  private implicit val system: ActorSystem[Nothing] = classicSystem.toTyped
  private implicit val exec: ExecutionContext = system.executionContext
  private implicit val scheduler: akka.actor.typed.Scheduler = system.scheduler

  // --- 1. 初始化标准聚合根 (CreditBalance) ---
  initCreditBalanceAggregate(sharding)

  // --- 2. 注册 Saga 定义并初始化 v3 引擎（所有定义共享一个 Coordinator 分片池）---
  MoneyTransferSagaDefinition.register
  FabSagaDefinition.register
  initSagaEngine[AppSagaContext](
    sharding = sharding,
    context = AppSagaContext(creditBalanceRepository, lotRepository),
    system = system)

  // --- 4. 初始化 Fab Lot + WorkOrder 聚合根 ---
  initLotAggregate(sharding)
  initWorkOrderAggregate(sharding)

  // --- 5. 初始化 Fab Saga 状态聚合（步骤由 v3 共享协调器驱动）---
  initFabSagaTransactor(
    coordinatorEntityKey = FabSagaService.fabSagaCoordinatorKey,
    sharding = sharding,
    sagaContext = AppSagaContext(creditBalanceRepository, lotRepository)
  )

  // --- 6. 初始化投影 (Projection) ---
  initMonthlySummaryProjection(system, sharding, monthlyRepository)
  initWorkOrderProjection(system)
  initFabLotProjection(system)
  initWorkOrderCompletionProjection(system)
  initFabSagaTransactionProjection(system)

  // --- 7. 初始化 FabPipelineExecutionActor (用于所有 Demo/Route 执行路径) ---
  initFabPipelineExecutionActor(sharding, pipelineContextFactory, pipelineStateFactory, pipelineStageResolver)

  // --- 8. 初始化银行批量充值+申购演示（ChainExecutionActor 分片 + Monarch 六阶段双链）---
  bankBatchDemoService.initSharding()

  println("🚀 [ApplicationBootstrap] All CQRS components initialized successfully.")

  // ====================================================================
  // FabPipelineExecutionActor recovery factories
  // ====================================================================

  /** Reconstructs FabDemoContext for crash recovery. Uses deterministic UUIDs. */
  private def pipelineContextFactory(scenarioId: String, workOrderId: String): FabDemoContext = {
    val scenario = scenarioId match {
      case "send-ahead-pilot" | "send-ahead-ocap" => StandardScenarios.sendAheadPilot
      case "scrap-downgrade"                      => StandardScenarios.scrapDowngrade
      case "sampling-demo"                        => StandardScenarios.samplingDemo
      case "hold-release"                         => StandardScenarios.holdRelease
      case _                                      => StandardScenarios.photoCell5Wafer
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

    // Reuse the demo's SHARED adapter (registered simulators survive the crash) —
    // a fresh adapter has an empty registry and every equipment stage fails UNEXPECTED.
    val adapter = fabDemoService.sharedM35Adapter.getOrElse(new ActorEquipmentAdapter())

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
      // Mirror FabDemoService.forM35ContextOcapRules so a recovered run evaluates the
      // same rule set the original run was started with.
      ocapRules = ocapRuleStore.getRules.filter { r =>
        val route = if (scenarioId == "send-ahead-pilot" || scenarioId == "send-ahead-ocap") "SEND-AHEAD-PILOT" else "PHOTOCELL-5WAFER"
        r.routeId == route || r.routeId.isEmpty
      }
    )
  }

  private def pipelineStateFactory(workOrderId: String): FabDemoState =
    FabDemoState(wafers = Map.empty)

  // NOTE: must be a `def`, not a `val` — line 77 passes it into sharding registration
  // during construction, before a body-order `val` is initialized (would capture null,
  // and every crash recovery would NPE at stageResolver(scenarioId)).
  private def pipelineStageResolver: String => Seq[FabScenarioPipeline.PipelineStage] = { scenarioId =>
    scenarioId match {
      case "photo-cell-5wafer" => Seq(FabScenarioPipeline.PhotoCellReworkPipeline)
      case "send-ahead-ocap" =>
        // Must mirror FabDemoService.buildSendAheadOcapScenario — recovery resumes the SAME
        // stage list the run was started with (keyed by StartExecution.scenarioId).
        val rules = ocapRuleStore.getRules.filter(r => r.routeId == "SEND-AHEAD-PILOT" || r.routeId.isEmpty)
        FabScenarioPipeline.m35SendAheadStages(rules)
      case "ocap-rework-crash" | "multi-workorder-chaos" =>
        val rules = ocapRuleStore.getRules.filter(r => r.routeId == "PHOTOCELL-5WAFER" || r.routeId.isEmpty)
        FabScenarioPipeline.m35BasicStages(rules)
      case _ => FabScenarioPipeline.resolveStages(scenarioId)
    }
  }
}