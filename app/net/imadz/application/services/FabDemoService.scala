package net.imadz.application.services

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.util.Timeout
import net.imadz.application.aggregates.LotAggregate.LotEntityTypeKey
import net.imadz.application.aggregates.LotProtocol._
import net.imadz.application.aggregates.WorkOrderAggregate
import net.imadz.application.aggregates.WorkOrderProtocol.{CreateWorkOrder, WorkOrderConfirmation}
import net.imadz.application.services.FabSagaService
import net.imadz.application.services.transactor.FabSagaProtocol.FabSagaConfirmation
import net.imadz.common.CommonTypes.Id
import net.imadz.application.chain.{FabDemoPipeline, FabFlowEngine, FabPipelineExecutionActor, FabScenarioPipeline}
import net.imadz.application.chain.FabScenarioPipeline.PipelineStage
import net.imadz.domain.events.{DemoStarted, FabSimulationEvent, RecoveryEvent, PipelineTimelineSnapshot, FaultInjected}
import net.imadz.domain.values.{Por, PorRepository}
import net.imadz.application.chain.FabExecutionModel.{FabDemoContext, FabDemoState, WaferInfo}
import net.imadz.fab.protocol.ActorEquipmentAdapter
import net.imadz.application.routing.RouteCompiler
import net.imadz.domain.routing._
import net.imadz.infrastructure.repositories.routing.{OcapRuleStore, RouteDefinitionStore}
import net.imadz.application.scenario.{FabSimulationScenario, StandardScenarios}
import net.imadz.fab.simulation._

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

@Singleton
class FabDemoService @Inject()(
  classicSystem: akka.actor.ActorSystem,
  sharding: ClusterSharding,
  fabSagaService: FabSagaService,
  ocapRuleStore: OcapRuleStore
) {
  private implicit val system: ActorSystem[Nothing] =
    akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps(classicSystem).toTyped
  private implicit val ec: ExecutionContext = system.executionContext
  private implicit val timeout: Timeout = 10.seconds

  // Publisher registry for bridging CreateWorkOrder (serializable) to pipeline (needs callback)
  private val publisherRegistry = new ConcurrentHashMap[String, Any => Unit]()

  // System-wide publisher, set once by FabDemoController at init.
  // Used by pipelineStarter for both initial runs AND crash-recovery replays,
  // where no per-request WebSocket publisher is available.
  @volatile private var systemWidePublisher: Option[FabSimulationEvent => Unit] = None

  /** Current fault probability for M3.5 equipment simulators (0.0–1.0). */
  @volatile var currentFaultProbability: Double = 0.0

  /** Shared M3.5 equipment adapter — persists across pipeline actor crashes so recovery can reuse simulator refs. */
  @volatile private var m35Adapter: Option[ActorEquipmentAdapter] = None

  /** Recovery (ApplicationBootstrap.pipelineContextFactory) must reuse THIS adapter —
    * a fresh one has an empty simulator registry and every equipment stage fails UNEXPECTED. */
  def sharedM35Adapter: Option[ActorEquipmentAdapter] = m35Adapter

  def setSystemWidePublisher(publisher: FabSimulationEvent => Unit): Unit = {
    systemWidePublisher = Some(publisher)
    FabDemoPublisher.systemPublisher = publisher
  }

  // WorkOrder aggregate init moved to FabBootstrap (no-arg init)

  /** Execute a dynamic POR work order (creates entities + runs pipeline). */
  private def runDynamicPor(
                             workOrderId: String, productId: String, routing: Por, waferIds: Seq[String],
                             publisher: FabSimulationEvent => Unit
  ): Future[FabDemoState] = {
    val syntheticScenario = FabSimulationScenario(
      scenarioId = productId,
      name = s"Dynamic: ${routing.productId}",
      description = s"POR-based dynamic routing (${routing.steps.size} steps, v${routing.version})",
      lotSize = waferIds.size,
      waferIds = waferIds,
      litho = EquipmentConfig("LITHO-01", "LITHO", processingTime = 8.seconds),
      lithoDetail = LithoConfig(waferCount = waferIds.size),
      cdSem = EquipmentConfig("CDSEM-01", "METROLOGY", processingTime = 5.seconds),
      cdSemDetail = CdSemConfig(waferIds = waferIds, targetCdNm = 32.0, waferOutcomes = waferIds.map(_ -> "PASS").toMap),
      amhs = AmhsConfig(routes = FabFlowEngine.DefaultRoutes.map { case (k, v) => k -> v }, maxConcurrentTransports = 5),
      stocker = StockerConfig("STOCKER-01", portCount = 4, loadTime = 2.seconds),
      decision = FabFlowEngine.DefaultDecisionConfig
    )

    // Deterministic UUIDs from workOrderId for idempotent recovery
    val waferUUIDs: Map[String, Id] = waferIds.map { wid =>
      wid -> UUID.nameUUIDFromBytes(s"$workOrderId-$wid".getBytes)
    }.toMap
    val sourceLotId: Id = UUID.nameUUIDFromBytes(s"$workOrderId-source-lot".getBytes)
    val reworkLotId: Id = UUID.nameUUIDFromBytes(s"$workOrderId-rework-lot".getBytes)
    val scrapLotId: Id  = UUID.nameUUIDFromBytes(s"$workOrderId-scrap-lot".getBytes)

    val lotRef = sharding.entityRefFor(LotEntityTypeKey, sourceLotId.toString)
    val reworkLotRef = sharding.entityRefFor(LotEntityTypeKey, reworkLotId.toString)
    val scrapLotRef = sharding.entityRefFor(LotEntityTypeKey, scrapLotId.toString)

    val sagaTxFn: (Id, Id, Set[Id], Set[String], Option[Id]) => Future[FabSagaConfirmation] =
      (srcId, tgtId, wids, names, existingTxId) => fabSagaService.transferWafers(srcId, tgtId, wids, names, existingTxId)

    val adapter = new ActorEquipmentAdapter()

    val ignoreLotReply = system.ignoreRef[LotConfirmation]

    val ctx = FabDemoContext(
      scenario = syntheticScenario,
      foupId = s"FOUP-${routing.productId}",
      lotRef = lotRef,
      reworkLotRef = reworkLotRef,
      waferUUIDs = waferUUIDs,
      sourceLotId = sourceLotId,
      reworkLotId = reworkLotId,
      adapter = adapter,
      publisher = publisher,
      ignoreLotReply = ignoreLotReply,
      sagaTx = sagaTxFn,
      speedMultiplier = 1.0,
      scrapLotRef = Some(scrapLotRef),
      scrapLotId = Some(scrapLotId),
      childLotRefs = Map("scrap" -> scrapLotRef, "rwk" -> reworkLotRef),
      childLotIds = Map("scrap" -> scrapLotId, "rwk" -> reworkLotId)
    )

    val initialState = FabDemoState(
      wafers = waferIds.map(wid => wid -> WaferInfo(wid)).toMap
    )

    publisher(DemoStarted(productId, routing.productId, waferIds.size, waferIds))

    val pipelineFn = FabFlowEngine.runRouting(routing, FabFlowEngine.DefaultDecisionConfig) _

    // Create entities (idempotent) then run pipeline via Actor
    // Child lots (rework, scrap) are created lazily inside the pipeline's saga split/scrap stages.
    val stages = Seq(FabScenarioPipeline.DynamicPorExecution(routing, FabFlowEngine.DefaultDecisionConfig))
    for {
      _ <- lotRef.ask[LotConfirmation](ref => CreateLot(s"FAB-$workOrderId", waferUUIDs.map(_.swap), ref, workOrderId = Some(workOrderId)))
      _ = spawnDynamicSimulators(routing, adapter, publisher, waferIds)
    } yield {
      val execRef = sharding.entityRefFor(FabPipelineExecutionActor.EntityKey, workOrderId)
      execRef ! FabPipelineExecutionActor.StartExecution(productId, workOrderId, initialState, stages, ctx,
        system.ignoreRef[FabPipelineExecutionActor.ExecutionReply])
      initialState
    }
  }

  /** Execute a work order from a RouteDefinition (Route Browser "Start" path).
   * Compiles the RouteDefinition to PipelineStages, creates entities, and runs them. */
  private def runFromRoute(
    workOrderId: String, productId: String, routeDef: net.imadz.domain.routing.RouteDefinition,
    waferIds: Seq[String], publisher: FabSimulationEvent => Unit
  ): Future[FabDemoState] = {
    val syntheticScenario = FabSimulationScenario(
      scenarioId = productId,
      name = s"Route: ${routeDef.name}",
      description = s"Route-based execution (${routeDef.nodes.size} nodes, v${routeDef.version})",
      lotSize = waferIds.size,
      waferIds = waferIds,
      litho = EquipmentConfig("LITHO-01", "LITHO", processingTime = 8.seconds),
      lithoDetail = LithoConfig(waferCount = waferIds.size),
      cdSem = EquipmentConfig("CDSEM-01", "METROLOGY", processingTime = 5.seconds),
      cdSemDetail = CdSemConfig(waferIds = waferIds, targetCdNm = 32.0, waferOutcomes = waferIds.map(_ -> "PASS").toMap),
      amhs = AmhsConfig(routes = FabFlowEngine.DefaultRoutes.map { case (k, v) => k -> v }, maxConcurrentTransports = 5),
      stocker = StockerConfig("STOCKER-01", portCount = 4, loadTime = 2.seconds),
      decision = FabFlowEngine.DefaultDecisionConfig
    )

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

    val sagaTxFn: (Id, Id, Set[Id], Set[String], Option[Id]) => Future[FabSagaConfirmation] =
      (srcId, tgtId, wids, names, existingTxId) => fabSagaService.transferWafers(srcId, tgtId, wids, names, existingTxId)

    val adapter = new ActorEquipmentAdapter()
    val ignoreLotReply = system.ignoreRef[LotConfirmation]

    val ctx = FabDemoContext(
      scenario = syntheticScenario,
      foupId = s"FOUP-${routeDef.productId}",
      lotRef = lotRef,
      reworkLotRef = reworkLotRef,
      waferUUIDs = waferUUIDs,
      sourceLotId = sourceLotId,
      reworkLotId = reworkLotId,
      adapter = adapter,
      publisher = publisher,
      ignoreLotReply = ignoreLotReply,
      sagaTx = sagaTxFn,
      speedMultiplier = 1.0,
      scrapLotRef = Some(scrapLotRef),
      scrapLotId = Some(scrapLotId),
      childLotRefs = Map(
        "pilot" -> pilotLotRef,
        "sample" -> sampleLotRef,
        "hold" -> holdLotRef,
        "scrap" -> scrapLotRef,
        "rwk" -> reworkLotRef
      ),
      childLotIds = Map(
        "pilot" -> pilotLotId,
        "sample" -> sampleLotId,
        "hold" -> holdLotId,
        "scrap" -> scrapLotId,
        "rwk" -> reworkLotId
      ),
      ocapRules = routeDef.ocapRules
    )

    val initialState = FabDemoState(
      wafers = waferIds.map(wid => wid -> WaferInfo(wid)).toMap
    )

    val stages = RouteCompiler.compile(routeDef)
    publisher(DemoStarted(productId, routeDef.name, waferIds.size, waferIds))

    for {
      _ <- lotRef.ask[LotConfirmation](ref => CreateLot(s"FAB-$workOrderId", waferUUIDs.map(_.swap), ref, workOrderId = Some(workOrderId)))
      _ = spawnMinimalSimulators(adapter, publisher)
    } yield {
      val execRef = sharding.entityRefFor(FabPipelineExecutionActor.EntityKey, workOrderId)
      execRef ! FabPipelineExecutionActor.StartExecution(productId, workOrderId, initialState, stages, ctx,
        system.ignoreRef[FabPipelineExecutionActor.ExecutionReply])
      initialState
    }
  }

  /** Spawn minimal simulators (Litho + CDSEM + AMHS + Stocker) for route-based execution. */
  private def spawnMinimalSimulators(
    adapter: ActorEquipmentAdapter, publisher: FabSimulationEvent => Unit
  ): Unit = {
    val lithoActor = system.systemActorOf(
      new LithographySimulator(LithoConfig(waferCount = 5))(EquipmentConfig("LITHO-01", "LITHO", processingTime = 8.seconds)),
      s"litho-route-${System.currentTimeMillis()}"
    )
    val cdSemActor = system.systemActorOf(
      new CdSemSimulator(CdSemConfig(waferIds = (1 to 5).map(i => s"WAFER-$i"), targetCdNm = 32.0,
        waferOutcomes = (1 to 5).map(i => s"WAFER-$i" -> "PASS").toMap))(EquipmentConfig("CDSEM-01", "METROLOGY", processingTime = 5.seconds)),
      s"cdsem-route-${System.currentTimeMillis()}"
    )
    val amhsActor = system.systemActorOf(
      new AmhsSimulator()(AmhsConfig(routes = FabFlowEngine.DefaultRoutes.map { case (k, v) => k -> v }, maxConcurrentTransports = 5), 1.0),
      s"amhs-route-${System.currentTimeMillis()}"
    )
    val stockerActor = system.systemActorOf(
      new StockerSimulator()(StockerConfig("STOCKER-01", portCount = 4, loadTime = 2.seconds)),
      s"stocker-route-${System.currentTimeMillis()}"
    )
    adapter.registerSimulator("LITHO-01", lithoActor)
    adapter.registerSimulator("CDSEM-01", cdSemActor)
    adapter.registerSimulator("AMHS", amhsActor)
    adapter.registerSimulator("STOCKER-01", stockerActor)

    publisher(net.imadz.domain.events.EquipmentStateChanged("LITHO-01", "LITHO", "Idle", None))
    publisher(net.imadz.domain.events.EquipmentStateChanged("CDSEM-01", "METROLOGY", "Idle", None))
    publisher(net.imadz.domain.events.EquipmentStateChanged("STOCKER-01", "STOCKER", "Idle", None))
  }

  /** Execute a static scenario work order. */
  private def runStaticScenario(
    workOrderId: String, scenarioId: String, waferIds: Seq[String],
    publisher: FabSimulationEvent => Unit
  ): Future[FabDemoState] = {
    val scenario = scenarioId match {
      case "photo-cell-5wafer" => StandardScenarios.photoCell5Wafer
      case "send-ahead-pilot"  => StandardScenarios.sendAheadPilot
      case "scrap-downgrade"   => StandardScenarios.scrapDowngrade
      case "sampling-demo"     => StandardScenarios.samplingDemo
      case "hold-release"      => StandardScenarios.holdRelease
      case "cxmt-dram-full-25" => StandardScenarios.cxmtDramFull25
      case _                   => StandardScenarios.photoCell5Wafer
    }
    val isRework = scenarioId == "photo-cell-5wafer" || scenarioId == "cxmt-dram-full-25"

    val waferUUIDs: Map[String, Id] = scenario.waferIds.map { wid =>
      wid -> UUID.nameUUIDFromBytes(s"$workOrderId-$wid".getBytes)
    }.toMap
    val sourceLotId: Id = UUID.nameUUIDFromBytes(s"$workOrderId-source-lot".getBytes)
    val reworkLotId: Id = UUID.nameUUIDFromBytes(s"$workOrderId-rework-lot".getBytes)
    val scrapLotId: Id  = UUID.nameUUIDFromBytes(s"$workOrderId-scrap-lot".getBytes)
    val pilotLotId: Id = UUID.nameUUIDFromBytes(s"$workOrderId-pilot-lot".getBytes)
    val sampleLotId: Id = UUID.nameUUIDFromBytes(s"$workOrderId-sample-lot".getBytes)
    val holdLotId: Id = UUID.nameUUIDFromBytes(s"$workOrderId-hold-lot".getBytes)

    val lotRef = sharding.entityRefFor(LotEntityTypeKey, sourceLotId.toString)
    val reworkLotRef = sharding.entityRefFor(LotEntityTypeKey, reworkLotId.toString)
    val scrapLotRef = sharding.entityRefFor(LotEntityTypeKey, scrapLotId.toString)
    val pilotLotRef = sharding.entityRefFor(LotEntityTypeKey, pilotLotId.toString)
    val sampleLotRef = sharding.entityRefFor(LotEntityTypeKey, sampleLotId.toString)
    val holdLotRef = sharding.entityRefFor(LotEntityTypeKey, holdLotId.toString)

    val sagaTxFn: (Id, Id, Set[Id], Set[String], Option[Id]) => Future[FabSagaConfirmation] =
      (srcId, tgtId, wids, names, existingTxId) => fabSagaService.transferWafers(srcId, tgtId, wids, names, existingTxId)

    val adapter = new ActorEquipmentAdapter()

    val ignoreLotReply = system.ignoreRef[LotConfirmation]

    val ctx = FabDemoContext(
      scenario = scenario,
      foupId = s"FOUP-${scenario.scenarioId}",
      lotRef = lotRef,
      reworkLotRef = reworkLotRef,
      waferUUIDs = waferUUIDs,
      sourceLotId = sourceLotId,
      reworkLotId = reworkLotId,
      adapter = adapter,
      publisher = publisher,
      ignoreLotReply = ignoreLotReply,
      sagaTx = sagaTxFn,
      speedMultiplier = 1.0,
      scrapLotRef = Some(scrapLotRef),
      scrapLotId = Some(scrapLotId),
      childLotRefs = Map(
        "pilot" -> pilotLotRef,
        "sample" -> sampleLotRef,
        "hold" -> holdLotRef,
        "scrap" -> scrapLotRef,
        "rwk" -> reworkLotRef
      ),
      childLotIds = Map(
        "pilot" -> pilotLotId,
        "sample" -> sampleLotId,
        "hold" -> holdLotId,
        "scrap" -> scrapLotId,
        "rwk" -> reworkLotId
      ),
      areaActorOf = net.imadz.application.actor.EquipmentAreaActor.Registry.entityRef
    )

    val initialState = FabDemoState(
      wafers = scenario.waferIds.map(wid => wid -> WaferInfo(wid)).toMap
    )

    publisher(DemoStarted(scenario.scenarioId, scenario.name, scenario.lotSize, scenario.waferIds))
    spawnSimulators(scenario, adapter, publisher)

    // Child lots are created lazily inside each pipeline's sagaSplit stage,
    // not upfront — avoids empty child lots appearing in the UI before split.

    val stages = if (isRework) Seq(FabScenarioPipeline.PhotoCellReworkPipeline)
                 else FabScenarioPipeline.resolveStages(scenarioId)

    for {
      _ <- lotRef.ask[LotConfirmation](ref => CreateLot(s"FAB-$workOrderId", waferUUIDs.map(_.swap), ref, workOrderId = Some(workOrderId)))
    } yield {
      val execRef = sharding.entityRefFor(FabPipelineExecutionActor.EntityKey, workOrderId)
      execRef ! FabPipelineExecutionActor.StartExecution(scenarioId, workOrderId, initialState, stages, ctx,
        system.ignoreRef[FabPipelineExecutionActor.ExecutionReply])
      initialState
    }
  }

  /**
   * Start a demo scenario using M2.5+ Chain-aligned FabChainExecutor.
   * Creates EventSourced Lot + Wafer aggregates, wires FabSagaService for TCC split/merge,
   * then runs the 11-stage pipeline via FabChainExecutor (EventSourcedBehavior).
   */
  def startDemo(scenarioId: String, publisher: FabSimulationEvent => Unit): Future[WorkOrderConfirmation] = {
    net.imadz.application.projection.FabDemoViewHandler.resetAll()
    net.imadz.application.projection.FabDemoViewProjection.resetChildLotRegistry()
    net.imadz.application.actor.EquipmentAreaActor.Registry.resetAll()
    val workOrderId = UUID.randomUUID().toString
    val scenario = scenarioId match {
      case "photo-cell-5wafer" => StandardScenarios.photoCell5Wafer
      case "send-ahead-pilot"  => StandardScenarios.sendAheadPilot
      case "scrap-downgrade"   => StandardScenarios.scrapDowngrade
      case "sampling-demo"     => StandardScenarios.samplingDemo
      case "hold-release"      => StandardScenarios.holdRelease
      case _                   => StandardScenarios.photoCell5Wafer
    }

    publisherRegistry.put(workOrderId, publisher.asInstanceOf[Any => Unit])
    val ref = sharding.entityRefFor(WorkOrderAggregate.WorkOrderEntityTypeKey, workOrderId)
    ref.ask[WorkOrderConfirmation](replyTo => CreateWorkOrder(scenarioId, scenario.waferIds, routeRef = None, replyTo = replyTo))
      .map { confirmation =>
        // Fire-and-forget: pipeline runs independently; WorkOrder tracks completion via Projection
        runStaticScenario(workOrderId, scenarioId, scenario.waferIds, publisher)
        confirmation
      }(ec)
  }

  private def spawnSimulators(
    scenario: net.imadz.application.scenario.FabSimulationScenario,
    adapter: ActorEquipmentAdapter,
    publisher: FabSimulationEvent => Unit
  ): Unit = {
    // Spawn equipment simulators and register with adapter
    import net.imadz.fab.simulation._
    val fp = currentFaultProbability
    val lithoConfig = scenario.litho.copy(faultProbability = fp)
    val cdSemConfig = scenario.cdSem.copy(faultProbability = fp)
    val lithoActor = system.systemActorOf(
      new LithographySimulator(scenario.lithoDetail)(lithoConfig),
      s"litho-${scenario.scenarioId}-${System.currentTimeMillis()}"
    )
    val cdSemActor = system.systemActorOf(
      new CdSemSimulator(scenario.cdSemDetail)(cdSemConfig),
      s"cdsem-${scenario.scenarioId}-${System.currentTimeMillis()}"
    )
    val amhsActor = system.systemActorOf(
      new AmhsSimulator()(scenario.amhs, 1.0),
      s"amhs-${scenario.scenarioId}-${System.currentTimeMillis()}"
    )
    val stockerActor = system.systemActorOf(
      new StockerSimulator()(scenario.stocker),
      s"stocker-${scenario.scenarioId}-${System.currentTimeMillis()}"
    )
    adapter.registerSimulator(scenario.litho.equipmentId, lithoActor)
    adapter.registerSimulator(scenario.cdSem.equipmentId, cdSemActor)
    adapter.registerSimulator("AMHS", amhsActor)
    adapter.registerSimulator(scenario.stocker.equipmentId, stockerActor)

    publisher(net.imadz.domain.events.EquipmentStateChanged(scenario.litho.equipmentId, "LITHO", "Idle", None))
    publisher(net.imadz.domain.events.EquipmentStateChanged(scenario.cdSem.equipmentId, "METROLOGY", "Idle", None))
    publisher(net.imadz.domain.events.EquipmentStateChanged(scenario.stocker.equipmentId, "STOCKER", "Idle", None))
  }

  /**
   * Start a demo using dynamic ProductRouting instead of a static scenario.
   *
   * Looks up the ProductRouting by productId, creates a 5-wafer demo lot,
   * spawns simulators for all equipment areas used in the routing,
   * then runs the dynamic FabFlowEngine via FabChainExecutor.
   */
  def startDemoWithProduct(productId: String, publisher: FabSimulationEvent => Unit): Future[WorkOrderConfirmation] = {
    net.imadz.application.projection.FabDemoViewHandler.resetAll()
    net.imadz.application.projection.FabDemoViewProjection.resetChildLotRegistry()
    net.imadz.application.actor.EquipmentAreaActor.Registry.resetAll()
    val routing = PorRepository.findByProductId(productId)
      .getOrElse(throw new IllegalArgumentException(s"Unknown product: $productId"))
    val waferIds = (1 to 5).map(i => s"WAFER-$i")
    val workOrderId = UUID.randomUUID().toString

    publisherRegistry.put(workOrderId, publisher.asInstanceOf[Any => Unit])
    val ref = sharding.entityRefFor(WorkOrderAggregate.WorkOrderEntityTypeKey, workOrderId)
    ref.ask[WorkOrderConfirmation](replyTo => CreateWorkOrder(productId, waferIds, routeRef = None, replyTo = replyTo))
      .map { confirmation =>
        runDynamicPor(workOrderId, productId, routing, waferIds, publisher)
        confirmation
      }(ec)
  }

  /** Start a demo from a RouteDefinition in RoutingRepository (Route Browser "Start" button). */
  def startDemoFromRoute(routeId: String, publisher: FabSimulationEvent => Unit): Future[WorkOrderConfirmation] = {
    net.imadz.application.projection.FabDemoViewHandler.resetAll()
    net.imadz.application.projection.FabDemoViewProjection.resetChildLotRegistry()
    net.imadz.application.actor.EquipmentAreaActor.Registry.resetAll()
    val routeDef = RouteDefinitionStore.getLatest(routeId)
      .getOrElse(throw new IllegalArgumentException(s"Unknown route: $routeId"))
    // Register in PorRepository so it also appears in the Start dropdown
    PorRepository.register(Por(
      productId = routeId,
      steps = routeDef.nodes.collect { case net.imadz.domain.routing.AtomicStep(_, _, op, _) =>
        net.imadz.domain.values.PorStep(
          stepId = op.toString.take(20),
          equipmentArea = net.imadz.fab.model.EquipmentArea.Lithography,
          recipeId = "DEFAULT",
          expectedDuration = 10.seconds
        )
      },
      version = routeDef.version
    ))
    val waferIds = (1 to 5).map(i => s"WAFER-$i")
    val workOrderId = UUID.randomUUID().toString

    publisherRegistry.put(workOrderId, publisher.asInstanceOf[Any => Unit])
    val ref = sharding.entityRefFor(WorkOrderAggregate.WorkOrderEntityTypeKey, workOrderId)
    ref.ask[WorkOrderConfirmation](replyTo => CreateWorkOrder(routeId, waferIds, routeRef = None, replyTo = replyTo))
      .map { confirmation =>
        runFromRoute(workOrderId, routeId, routeDef, waferIds, publisher)
        confirmation
      }(ec)
  }

  /** Spawn generic equipment simulators for all areas used in a routing. */
  private def spawnDynamicSimulators(
                                      routing: Por,
                                      adapter: ActorEquipmentAdapter,
                                      publisher: FabSimulationEvent => Unit,
                                      waferIds: Seq[String]
  ): Unit = {
    val fp = currentFaultProbability
    val areaIds = routing.steps.map(_.equipmentArea.areaId).distinct
    val equipIds = areaIds.flatMap(aid => FabFlowEngine.AreaToEquipmentId.get(aid))

    // Generic processing simulator for each unique equipment
    equipIds.foreach { eid =>
      val areaType = routing.steps.find(s => FabFlowEngine.AreaToEquipmentId.get(s.equipmentArea.areaId).contains(eid))
        .map(_.equipmentArea.areaId).getOrElse(eid)
      val equipCfg = EquipmentConfig(eid, areaType, processingTime = 8.seconds, faultProbability = fp)
      val actor = system.systemActorOf(
        new GenericEquipmentSimulator().apply(equipCfg),
        s"dyn-equip-$eid-${System.currentTimeMillis()}"
      )
      adapter.registerSimulator(eid, actor)
      publisher(net.imadz.domain.events.EquipmentStateChanged(eid, areaType, "Idle", None))
    }

    // CD-SEM simulator (for measurement)
    val cdSemId = FabFlowEngine.CdsemEquipId
    val cdSemCfg = CdSemConfig(
      waferIds = waferIds,
      targetCdNm = 32.0,
      waferOutcomes = Map.empty // will use random generation
    )
    val cdSemActor = system.systemActorOf(
      new CdSemSimulator(cdSemCfg)(EquipmentConfig(cdSemId, "METROLOGY", processingTime = 5.seconds, faultProbability = fp)),
      s"dyn-cdsem-${System.currentTimeMillis()}"
    )
    adapter.registerSimulator(cdSemId, cdSemActor)
    publisher(net.imadz.domain.events.EquipmentStateChanged(cdSemId, "METROLOGY", "Idle", None))

    // AMHS simulator
    val amhsActor = system.systemActorOf(
      new AmhsSimulator()(AmhsConfig(routes = FabFlowEngine.DefaultRoutes.map { case (k, v) => k -> v },
        maxConcurrentTransports = 5), 1.0),
      s"dyn-amhs-${System.currentTimeMillis()}"
    )
    adapter.registerSimulator("AMHS", amhsActor)

    // Stocker simulator
    val stockerId = FabFlowEngine.StockerEquipId
    val stockerActor = system.systemActorOf(
      new StockerSimulator()(StockerConfig(stockerId, portCount = 4, loadTime = 2.seconds)),
      s"dyn-stocker-${System.currentTimeMillis()}"
    )
    adapter.registerSimulator(stockerId, stockerActor)
    publisher(net.imadz.domain.events.EquipmentStateChanged(stockerId, "STOCKER", "Idle", None))
  }

  // ===========================================================================
  // Route Graph (visual flowchart for the demo page)
  // ===========================================================================

  def getRouteGraph(scenarioId: String): Map[String, Any] = {
    scenarioId match {
      case "photo-cell-5wafer" => reworkRouteGraph
      case "send-ahead-pilot"  => sendAheadRouteGraph
      case "scrap-downgrade"   => scrapRouteGraph
      case "sampling-demo"     => samplingRouteGraph
      case "hold-release"      => holdReleaseRouteGraph
      case productId if PorRepository.findByProductId(productId).isDefined =>
        val routing = PorRepository.findByProductId(productId).get
        generateDynamicRouteGraph(routing)
      case _ => reworkRouteGraph
    }
  }

  private def equipmentNode(id: String, area: String, meta: String = "", x: Int = 0, y: Int = 0, w: Int = 120, h: Int = 52, recipe: String = ""): Map[String, Any] =
    Map("id" -> id, "type" -> "equipment", "label" -> s"$id\n$area", "meta" -> (if(recipe.nonEmpty) recipe else if (meta.nonEmpty) meta else area),
      "x" -> x, "y" -> y, "w" -> w, "h" -> h)

  private def transportNode(id: String, from: String, to: String, x: Int, y: Int): Map[String, Any] =
    Map("id" -> id, "type" -> "transport", "label" -> s"$from→$to", "meta" -> "", "x" -> x, "y" -> y, "w" -> 80, "h" -> 30)

  private def decisionNode(id: String, label: String, x: Int, y: Int): Map[String, Any] =
    Map("id" -> id, "type" -> "decision", "label" -> label, "meta" -> "", "x" -> x, "y" -> y, "w" -> 100, "h" -> 50)

  private def sagaNode(id: String, sagaType: String, lotKey: String, x: Int, y: Int): Map[String, Any] =
    Map("id" -> id, "type" -> "saga", "label" -> s"$sagaType\n$lotKey", "meta" -> lotKey, "x" -> x, "y" -> y, "w" -> 84, "h" -> 52,
      "sagaType" -> sagaType, "lotKey" -> lotKey)

  private def classifyNode(id: String, x: Int, y: Int): Map[String, Any] =
    Map("id" -> id, "type" -> "classify", "label" -> "Classify", "meta" -> "", "x" -> x, "y" -> y, "w" -> 88, "h" -> 44)

  private def edge(from: String, to: String, label: String = "", edgeType: String = "material"): Map[String, String] =
    (Map("from" -> from, "to" -> to, "type" -> edgeType) ++ (if(label.nonEmpty) Map("label" -> label) else Map.empty)).asInstanceOf[Map[String, String]]

  // Route: photo-cell-5wafer (Rework)
  private val reworkRouteGraph: Map[String, Any] = {
    val y0 = 80; val y1 = 180; val xStep = 160
    Map("name" -> "Rework (photo-cell-5wafer)", "description" -> "Litho → CDSEM → Classify → Split Rework → Rework Loop → Merge → Seal",
      "nodes" -> Seq(
        equipmentNode("n-load","STOCKER-01","STOCKER",20,y0), transportNode("n-t1","STOCKER","LITHO",150,y0),
        equipmentNode("n-litho","LITHO-01","LITHO",250,y0, w=130, recipe="LITHO-28-001"),
        transportNode("n-t2","LITHO","CDSEM",400,y0),
        equipmentNode("n-cdsem","CDSEM-01","MET",500,y0), classifyNode("n-cls",640,y0),
        sagaNode("n-split","Split","rwk",780,y0), equipmentNode("n-rwk-litho","LITHO-01","LITHO\nREWORK",20,y1, w=130),
        transportNode("n-rwk-t1","LITHO","CDSEM",170,y1), equipmentNode("n-rwk-cdsem","CDSEM-01","MET",270,y1),
        classifyNode("n-rwk-cls",410,y1), sagaNode("n-merge","Merge","rwk",530,y1),
        decisionNode("n-dec","All\nPASS?",680,y1), transportNode("n-t3","CDSEM","STOCKER",800,y1),
        equipmentNode("n-seal","STOCKER-01","Seal",920,y1)
      ),
      "edges" -> Seq(
        edge("n-load","n-t1"), edge("n-t1","n-litho"), edge("n-litho","n-t2"), edge("n-t2","n-cdsem"),
        edge("n-cdsem","n-cls"), edge("n-cls","n-split"), edge("n-split","n-rwk-litho"),
        edge("n-rwk-litho","n-rwk-t1"), edge("n-rwk-t1","n-rwk-cdsem"), edge("n-rwk-cdsem","n-rwk-cls"),
        edge("n-rwk-cls","n-merge"), edge("n-merge","n-dec"), edge("n-dec","n-t3","PASS", "material"),
        edge("n-dec","n-rwk-litho","FAIL","exception"), edge("n-t3","n-seal")
      ))
  }

  // Route: send-ahead-pilot
  private val sendAheadRouteGraph: Map[String, Any] = {
    val y0 = 70; val y1 = 170; val xStep = 150
    Map("name" -> "Send-Ahead Pilot", "description" -> "Split Pilot → Pilot Litho+CDSEM → Classify → Merge → Main Batch → Seal",
      "nodes" -> Seq(
        equipmentNode("n-load","STOCKER-01","STOCKER",20,y0), sagaNode("n-split","Split","pilot",160,y0),
        transportNode("n-p-t1","STOCKER","LITHO",270,y0), equipmentNode("n-p-litho","LITHO-01","LITHO\nPILOT",370,y0,w=130),
        transportNode("n-p-t2","LITHO","CDSEM",530,y0), equipmentNode("n-p-cdsem","CDSEM-01","MET",630,y0),
        classifyNode("n-p-cls",750,y0), sagaNode("n-merge","Merge","pilot",860,y0),
        decisionNode("n-dec","Pilot\nOK?",980,y0),
        transportNode("n-m-t1","STOCKER","LITHO",270,y1), equipmentNode("n-m-litho","LITHO-01","LITHO\nMAIN",390,y1,w=130),
        transportNode("n-m-t2","LITHO","CDSEM",550,y1), equipmentNode("n-m-cdsem","CDSEM-01","MET",670,y1),
        classifyNode("n-m-cls",790,y1), transportNode("n-m-t3","CDSEM","STOCKER",900,y1),
        equipmentNode("n-seal","STOCKER-01","Seal",1020,y1)
      ),
      "edges" -> Seq(
        edge("n-load","n-split"), edge("n-split","n-p-t1"), edge("n-p-t1","n-p-litho"), edge("n-p-litho","n-p-t2"),
        edge("n-p-t2","n-p-cdsem"), edge("n-p-cdsem","n-p-cls"), edge("n-p-cls","n-merge"),
        edge("n-merge","n-dec"), edge("n-dec","n-m-t1","PASS","material"),
        edge("n-dec","n-seal","FAIL","exception"), edge("n-m-t1","n-m-litho"),
        edge("n-m-litho","n-m-t2"), edge("n-m-t2","n-m-cdsem"), edge("n-m-cdsem","n-m-cls"),
        edge("n-m-cls","n-m-t3"), edge("n-m-t3","n-seal")
      ))
  }

  // Route: scrap-downgrade
  private val scrapRouteGraph: Map[String, Any] = {
    val y0 = 100
    Map("name" -> "Scrap & Downgrade", "description" -> "Litho → CDSEM → Classify → Scrap W3 → Seal",
      "nodes" -> Seq(
        equipmentNode("n-load","STOCKER-01","STOCKER",20,y0), transportNode("n-t1","STOCKER","LITHO",150,y0),
        equipmentNode("n-litho","LITHO-01","LITHO",250,y0,w=130,recipe="LITHO-28-001"),
        transportNode("n-t2","LITHO","CDSEM",400,y0), equipmentNode("n-cdsem","CDSEM-01","MET",500,y0),
        classifyNode("n-cls",640,y0), sagaNode("n-split","Split","scrap",760,y0),
        decisionNode("n-dec","Has\nScrap?",880,y0), transportNode("n-t3","CDSEM","STOCKER",1020,y0),
        equipmentNode("n-seal","STOCKER-01","Seal",1140,y0)
      ),
      "edges" -> Seq(
        edge("n-load","n-t1"), edge("n-t1","n-litho"), edge("n-litho","n-t2"), edge("n-t2","n-cdsem"),
        edge("n-cdsem","n-cls"), edge("n-cls","n-split"), edge("n-split","n-dec"),
        edge("n-dec","n-t3","No Scrap","material"), edge("n-dec","n-seal","Scrap→SCP","exception"),
        edge("n-t3","n-seal")
      ))
  }

  // Route: sampling-demo
  private val samplingRouteGraph: Map[String, Any] = {
    val y0 = 100
    Map("name" -> "Metrology Sampling", "description" -> "Split Sample → CDSEM → Classify → Merge → Seal",
      "nodes" -> Seq(
        equipmentNode("n-load","STOCKER-01","STOCKER",20,y0), sagaNode("n-split","Split","sample",160,y0),
        transportNode("n-s-t1","STOCKER","CDSEM",270,y0), equipmentNode("n-cdsem","CDSEM-01","MET",370,y0),
        classifyNode("n-cls",500,y0), sagaNode("n-merge","Merge","sample",620,y0),
        transportNode("n-t3","CDSEM","STOCKER",730,y0), equipmentNode("n-seal","STOCKER-01","Seal",850,y0)
      ),
      "edges" -> Seq(
        edge("n-load","n-split"), edge("n-split","n-s-t1"), edge("n-s-t1","n-cdsem"),
        edge("n-cdsem","n-cls"), edge("n-cls","n-merge"), edge("n-merge","n-t3"),
        edge("n-t3","n-seal")
      ))
  }

  // Route: hold-release
  private val holdReleaseRouteGraph: Map[String, Any] = {
    val y0 = 70; val y1 = 170
    Map("name" -> "Hold & Release", "description" -> "Litho → CDSEM → Classify → Hold → Review → Release → Merge → Seal",
      "nodes" -> Seq(
        equipmentNode("n-load","STOCKER-01","STOCKER",20,y0), transportNode("n-t1","STOCKER","LITHO",150,y0),
        equipmentNode("n-litho","LITHO-01","LITHO",250,y0,w=130,recipe="LITHO-28-001"),
        transportNode("n-t2","LITHO","CDSEM",400,y0), equipmentNode("n-cdsem","CDSEM-01","MET",500,y0),
        classifyNode("n-cls",640,y0), sagaNode("n-split","Split","hold",770,y0),
        Map("id"->"n-hold","type"->"hold","label"->"Hold\nReview","x"->880,"y"->y0,"w"->88,"h"->50),
        decisionNode("n-dec","OK?",1000,y0),
        sagaNode("n-merge","Merge","hold",770,y1),
        transportNode("n-t3","CDSEM","STOCKER",880,y1), equipmentNode("n-seal","STOCKER-01","Seal",1000,y1)
      ),
      "edges" -> Seq(
        edge("n-load","n-t1"), edge("n-t1","n-litho"), edge("n-litho","n-t2"), edge("n-t2","n-cdsem"),
        edge("n-cdsem","n-cls"), edge("n-cls","n-split"), edge("n-split","n-hold"),
        edge("n-hold","n-dec"), edge("n-dec","n-merge","PASS","material"),
        edge("n-dec","n-seal","SCRAP","exception"), edge("n-merge","n-t3"), edge("n-t3","n-seal")
      ))
  }

  private def generateDynamicRouteGraph(routing: Por): Map[String, Any] = {
    val y = 100; var x = 20; val dx = 140
    val buf = scala.collection.mutable.ListBuffer.empty[Map[String, Any]]
    buf += equipmentNode("n-load", "STOCKER-01", "STOCKER", x, y); x += dx
    routing.steps.foreach { step =>
      val equipId = net.imadz.application.chain.FabFlowEngine.AreaToEquipmentId.getOrElse(step.equipmentArea.areaId, s"${step.equipmentArea.areaId}-01")
      buf += transportNode(s"n-t-${step.stepId}", "prev", step.equipmentArea.areaId, x, y); x += 80
      buf += equipmentNode(s"n-${step.stepId}", equipId, s"${step.equipmentArea.areaId}\n${step.recipeId}", x, y, w = 130)
      x += dx
    }
    buf += transportNode("n-t-seal", "prev", "STOCKER", x, y); x += 80
    buf += equipmentNode("n-seal", "STOCKER-01", "Seal", x, y)

    val edgesBuf = scala.collection.mutable.ListBuffer.empty[Map[String, String]]
    val nodeIds = buf.map(_("id")).toSeq
    nodeIds.sliding(2).foreach {
      case Seq(a, b) => edgesBuf += edge(a.toString, b.toString)
      case _ => ()
    }
    Map("name" -> s"Dynamic: ${routing.productId}",
      "description" -> s"${routing.steps.size} steps, v${routing.version}",
      "nodes" -> buf.toSeq, "edges" -> edgesBuf.toSeq)
  }

  def getScenarios: Seq[Map[String, String]] = {
    val staticScenarios = Seq(
      Map("id" -> "photo-cell-5wafer", "name" -> "Rework (5 wafers)", "type" -> "rework"),
      Map("id" -> "cxmt-dram-full-25", "name" -> "CXMT DRAM Full Lot (25 wafers)", "type" -> "rework"),
      Map("id" -> "send-ahead-pilot", "name" -> "Send-Ahead Pilot (5 wafers)", "type" -> "send-ahead"),
      Map("id" -> "scrap-downgrade", "name" -> "Scrap & Downgrade (3 wafers)", "type" -> "scrap"),
      Map("id" -> "sampling-demo", "name" -> "Metrology Sampling (6 wafers)", "type" -> "sampling"),
      Map("id" -> "hold-release", "name" -> "Hold & Release (5 wafers)", "type" -> "hold")
    )
    val dynamicProducts = PorRepository.listProducts.map { r =>
      Map("id" -> r.productId, "name" -> s"Dynamic: ${r.productId} (${r.steps.size} steps)", "type" -> "dynamic-routing")
    }
    staticScenarios ++ dynamicProducts
  }

  def getScenarioLedger(scenarioId: String): Map[String, Any] = {
    val (steps, name, lotReworkLabel) = scenarioId match {
      case "photo-cell-5wafer" => (photoCellLedger, "Photo Cell 5-Wafer — 2 PASS + 2 Rework → PASS + 1 SCRAP", "Rework")
      case "send-ahead-pilot"  => (sendAheadLedger,  "Send-Ahead Pilot — 1 wafer Pilot → PASS → Merge back", "Pilot")
      case "scrap-downgrade"   => (scrapLedger,      "Scrap & Downgrade — Direct Scrap, no child lot", "—")
      case "sampling-demo"     => (samplingLedger,   "Metrology Sampling — 2 sampled, rest skipped", "Sample")
      case "hold-release"      => (holdReleaseLedger,"Hold & Release — Borderline → Hold → Review → Release", "Hold")
      case "cxmt-dram-full-25" => (photoCellLedger, "CXMT DRAM Full Lot (25) — full-FOUP production batch: rework split + scrap", "Rework")
      case productId if PorRepository.findByProductId(productId).isDefined =>
        val routing = PorRepository.findByProductId(productId).get
        (generateRoutingLedger(routing), s"Dynamic POR: ${routing.productId} (${routing.steps.size} steps)", "—")
      case _ => (photoCellLedger, "Photo Cell 5-Wafer — 2 PASS + 2 Rework → PASS + 1 SCRAP", "Rework")
    }
    Map("scenarioId" -> scenarioId, "name" -> name, "steps" -> steps, "lotReworkLabel" -> lotReworkLabel)
  }

  // ===========================================================================
  // Static Scenario Ledgers
  // ===========================================================================

  private val photoCellLedger: Seq[Map[String, String]] = Seq(
    Map("seq" -> "0",  "event" -> "Load FOUP from Stocker (5 wafers)",      "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "—",         "saga" -> "—",      "phase" -> "Load"),
    Map("seq" -> "1",  "event" -> "Transport: STOCKER → LITHO",              "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "—",         "saga" -> "—",      "phase" -> "Transport"),
    Map("seq" -> "2",  "event" -> "FOUP arrives at Litho",                   "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "—",         "saga" -> "—",      "phase" -> "AtEqp"),
    Map("seq" -> "3",  "event" -> "Litho: ProcessRecipe LITHO-28-001",       "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "(process)", "saga" -> "—",      "phase" -> "Process"),
    Map("seq" -> "4",  "event" -> "Transport: LITHO → CD-SEM",               "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "—",         "saga" -> "—",      "phase" -> "Transport"),
    Map("seq" -> "5",  "event" -> "FOUP arrives at CD-SEM",                  "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "—",         "saga" -> "—",      "phase" -> "AtEqp"),
    Map("seq" -> "6",  "event" -> "CD-SEM: Measure CD (5 wafers)",           "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "(measure)", "saga" -> "—",      "phase" -> "Measure"),
    Map("seq" -> "7",  "event" -> "Classify: W1=PASS W2=PASS W3=FAIL W4=FAIL W5=SCRAP", "lotSource" -> "—", "lotRework" -> "—", "wafer" -> "2PASS 2FAIL 1SCRAP", "saga" -> "—", "phase" -> "Decide"),
    Map("seq" -> "8",  "event" -> "Split: W3,W4 → Rework Lot",       "lotSource" -> "Active(3w)", "lotRework" -> "Active(2w)", "wafer" -> "W3,W4→rework", "saga" -> "Initiated", "phase" -> "Split"),
    Map("seq" -> "9",  "event" -> "Transport Rework: CDSEM → LITHO",          "lotSource" -> "(read)",   "lotRework" -> "(read)",   "wafer" -> "(read)",    "saga" -> "Committed", "phase" -> "Rework"),
    Map("seq" -> "10", "event" -> "FOUP arrives at Litho (Rework pass #1)",   "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "—",         "saga" -> "—",      "phase" -> "AtEqp"),
    Map("seq" -> "11", "event" -> "Rework Litho: REWORK-LITHO-001",           "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "(rework)",  "saga" -> "—",      "phase" -> "Process"),
    Map("seq" -> "12", "event" -> "Transport: LITHO → CD-SEM (rework FOUP)",  "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "—",         "saga" -> "—",      "phase" -> "Transport"),
    Map("seq" -> "13", "event" -> "FOUP arrives at CD-SEM",                   "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "—",         "saga" -> "—",      "phase" -> "AtEqp"),
    Map("seq" -> "14", "event" -> "CD-SEM: Measure reworked wafers (W3,W4)",  "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "(measure)", "saga" -> "—",      "phase" -> "Measure"),
    Map("seq" -> "15", "event" -> "Classify: W3=PASS W4=PASS (rework ✓)",     "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "PASS×2",    "saga" -> "—",      "phase" -> "Decide"),
    Map("seq" -> "16", "event" -> "Merge: W3,W4 → Source Lot",        "lotSource" -> "Active(5w)", "lotRework" -> "Empty", "wafer" -> "W3,W4→source","saga" -> "Completed","phase" -> "Return"),
    Map("seq" -> "17", "event" -> "Return FOUP to Stocker + Demo Completed",  "lotSource" -> "Sealed",   "lotRework" -> "—",     "wafer" -> "4PASS 1SCRAP","saga" -> "—",     "phase" -> "Complete"),
  )

  // -- Send-Ahead Pilot (PASS path) --
  private val sendAheadLedger: Seq[Map[String, String]] = Seq(
    Map("seq" -> "0",  "event" -> "Load FOUP from Stocker (5 wafers)",       "lotSource" -> "—",        "lotRework" -> "—",      "wafer" -> "—",         "saga" -> "—",      "phase" -> "Load"),
    Map("seq" -> "1",  "event" -> "Split: W1 → Pilot Lot (1 wafer)",          "lotSource" -> "Active(4w)","lotRework" -> "Active(1w)","wafer" -> "W1→pilot",  "saga" -> "Initiated","phase" -> "Split"),
    Map("seq" -> "2",  "event" -> "Transport Pilot: STOCKER → LITHO",         "lotSource" -> "(wait)",   "lotRework" -> "(transit)","wafer" -> "—",       "saga" -> "Committed","phase" -> "Transport"),
    Map("seq" -> "3",  "event" -> "Pilot arrives at Litho",                   "lotSource" -> "—",        "lotRework" -> "—",      "wafer" -> "—",         "saga" -> "—",      "phase" -> "AtEqp"),
    Map("seq" -> "4",  "event" -> "Pilot Litho: PILOT-RECIPE-001",            "lotSource" -> "—",        "lotRework" -> "—",      "wafer" -> "(process)", "saga" -> "—",      "phase" -> "Process"),
    Map("seq" -> "5",  "event" -> "Transport Pilot: LITHO → CD-SEM",          "lotSource" -> "—",        "lotRework" -> "(transit)","wafer" -> "—",       "saga" -> "—",      "phase" -> "Transport"),
    Map("seq" -> "6",  "event" -> "Pilot arrives at CD-SEM",                  "lotSource" -> "—",        "lotRework" -> "—",      "wafer" -> "—",         "saga" -> "—",      "phase" -> "AtEqp"),
    Map("seq" -> "7",  "event" -> "CD-SEM: Measure pilot wafer (W1)",         "lotSource" -> "—",        "lotRework" -> "—",      "wafer" -> "(measure)", "saga" -> "—",      "phase" -> "Measure"),
    Map("seq" -> "8",  "event" -> "Classify: W1=PASS → Pilot OK",             "lotSource" -> "—",        "lotRework" -> "—",      "wafer" -> "PASS×1",    "saga" -> "—",      "phase" -> "Decide"),
    Map("seq" -> "9",  "event" -> "Merge: Pilot Lot → Source Lot",            "lotSource" -> "Active(5w)","lotRework" -> "Empty",  "wafer" -> "W1→source", "saga" -> "Completed","phase" -> "Merge"),
    Map("seq" -> "10", "event" -> "Transport: CDSEM → LITHO (main batch)",    "lotSource" -> "(transit)","lotRework" -> "—",      "wafer" -> "—",         "saga" -> "—",      "phase" -> "Transport"),
    Map("seq" -> "11", "event" -> "FOUP arrives at Litho",                    "lotSource" -> "—",        "lotRework" -> "—",      "wafer" -> "—",         "saga" -> "—",      "phase" -> "AtEqp"),
    Map("seq" -> "12", "event" -> "Litho: ProcessRecipe LITHO-28-001",        "lotSource" -> "—",        "lotRework" -> "—",      "wafer" -> "(process)", "saga" -> "—",      "phase" -> "Process"),
    Map("seq" -> "13", "event" -> "Transport: LITHO → CD-SEM",                "lotSource" -> "—",        "lotRework" -> "—",      "wafer" -> "—",         "saga" -> "—",      "phase" -> "Transport"),
    Map("seq" -> "14", "event" -> "FOUP arrives at CD-SEM",                   "lotSource" -> "—",        "lotRework" -> "—",      "wafer" -> "—",         "saga" -> "—",      "phase" -> "AtEqp"),
    Map("seq" -> "15", "event" -> "CD-SEM: Measure CD (5 wafers)",            "lotSource" -> "—",        "lotRework" -> "—",      "wafer" -> "(measure)", "saga" -> "—",      "phase" -> "Measure"),
    Map("seq" -> "16", "event" -> "Classify: Final PASS/FAIL/SCRAP",          "lotSource" -> "—",        "lotRework" -> "—",      "wafer" -> "PASS×4 SCRAP×1","saga" -> "—",  "phase" -> "Decide"),
    Map("seq" -> "17", "event" -> "Return FOUP to Stocker + Demo Completed",  "lotSource" -> "Sealed",   "lotRework" -> "—",      "wafer" -> "4PASS 1SCRAP","saga" -> "—",    "phase" -> "Complete"),
  )

  // -- Scrap & Downgrade --
  private val scrapLedger: Seq[Map[String, String]] = Seq(
    Map("seq" -> "0",  "event" -> "Load FOUP from Stocker (5 wafers)",       "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "—",         "saga" -> "—",      "phase" -> "Load"),
    Map("seq" -> "1",  "event" -> "Transport: STOCKER → LITHO",              "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "—",         "saga" -> "—",      "phase" -> "Transport"),
    Map("seq" -> "2",  "event" -> "FOUP arrives at Litho",                   "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "—",         "saga" -> "—",      "phase" -> "AtEqp"),
    Map("seq" -> "3",  "event" -> "Litho: ProcessRecipe LITHO-28-001",       "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "(process)", "saga" -> "—",      "phase" -> "Process"),
    Map("seq" -> "4",  "event" -> "Transport: LITHO → CD-SEM",               "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "—",         "saga" -> "—",      "phase" -> "Transport"),
    Map("seq" -> "5",  "event" -> "FOUP arrives at CD-SEM",                  "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "—",         "saga" -> "—",      "phase" -> "AtEqp"),
    Map("seq" -> "6",  "event" -> "CD-SEM: Measure CD (5 wafers)",           "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "(measure)", "saga" -> "—",      "phase" -> "Measure"),
    Map("seq" -> "7",  "event" -> "Classify: W1=PASS W2=PASS W3=PASS W4=FAIL W5=SCRAP", "lotSource" -> "—", "lotRework" -> "—", "wafer" -> "3PASS 1FAIL 1SCRAP", "saga" -> "—", "phase" -> "Decide"),
    Map("seq" -> "8",  "event" -> "Scrap: W5 → Terminated (no child lot)",   "lotSource" -> "Active(4w)","lotRework" -> "—",     "wafer" -> "W5→Scrapped","saga" -> "—",     "phase" -> "Scrap"),
    Map("seq" -> "9",  "event" -> "Return FOUP to Stocker + Demo Completed", "lotSource" -> "Sealed",   "lotRework" -> "—",     "wafer" -> "4PASS 1SCRAP","saga" -> "—",    "phase" -> "Complete"),
  )

  // -- Metrology Sampling --
  private val samplingLedger: Seq[Map[String, String]] = Seq(
    Map("seq" -> "0",  "event" -> "Load FOUP from Stocker (6 wafers)",       "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "—",         "saga" -> "—",      "phase" -> "Load"),
    Map("seq" -> "1",  "event" -> "Split: W1,W2 → Sample Lot (2 wafers)",     "lotSource" -> "Active(4w)","lotRework" -> "Active(2w)","wafer" -> "W1,W2→sample","saga" -> "Initiated","phase" -> "Split"),
    Map("seq" -> "2",  "event" -> "Transport Sample: STOCKER → CD-SEM",       "lotSource" -> "(wait)",   "lotRework" -> "(transit)","wafer" -> "—",      "saga" -> "Committed","phase" -> "Transport"),
    Map("seq" -> "3",  "event" -> "Sample arrives at CD-SEM",                 "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "—",         "saga" -> "—",      "phase" -> "AtEqp"),
    Map("seq" -> "4",  "event" -> "CD-SEM: Measure sampled wafers (W1,W2)",   "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "(measure)", "saga" -> "—",      "phase" -> "Measure"),
    Map("seq" -> "5",  "event" -> "Classify: W1=PASS W2=PASS → Sample OK",    "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "PASS×2",    "saga" -> "—",      "phase" -> "Decide"),
    Map("seq" -> "6",  "event" -> "Merge: Sample Lot → Source Lot",           "lotSource" -> "Active(6w)","lotRework" -> "Empty", "wafer" -> "W1,W2→source","saga" -> "Completed","phase" -> "Merge"),
    Map("seq" -> "7",  "event" -> "Source wafers W3-W6: Skipped (no measure)","lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "4 Skipped", "saga" -> "—",      "phase" -> "Skip"),
    Map("seq" -> "8",  "event" -> "Return FOUP to Stocker + Demo Completed",  "lotSource" -> "Sealed",   "lotRework" -> "—",     "wafer" -> "6 Active",  "saga" -> "—",      "phase" -> "Complete"),
  )

  // -- Hold & Release (PASS path: Review approved) --
  private val holdReleaseLedger: Seq[Map[String, String]] = Seq(
    Map("seq" -> "0",  "event" -> "Load FOUP from Stocker (5 wafers)",       "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "—",         "saga" -> "—",      "phase" -> "Load"),
    Map("seq" -> "1",  "event" -> "Transport: STOCKER → LITHO",              "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "—",         "saga" -> "—",      "phase" -> "Transport"),
    Map("seq" -> "2",  "event" -> "FOUP arrives at Litho",                   "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "—",         "saga" -> "—",      "phase" -> "AtEqp"),
    Map("seq" -> "3",  "event" -> "Litho: ProcessRecipe LITHO-28-001",       "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "(process)", "saga" -> "—",      "phase" -> "Process"),
    Map("seq" -> "4",  "event" -> "Transport: LITHO → CD-SEM",               "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "—",         "saga" -> "—",      "phase" -> "Transport"),
    Map("seq" -> "5",  "event" -> "FOUP arrives at CD-SEM",                  "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "—",         "saga" -> "—",      "phase" -> "AtEqp"),
    Map("seq" -> "6",  "event" -> "CD-SEM: Measure CD (5 wafers)",           "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "(measure)", "saga" -> "—",      "phase" -> "Measure"),
    Map("seq" -> "7",  "event" -> "Classify: W3=BORDERLINE → Hold for Review","lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "4PASS 1BORDERLINE","saga" -> "—", "phase" -> "Decide"),
    Map("seq" -> "8",  "event" -> "Split: W3 → Hold Lot",                    "lotSource" -> "Active(4w)","lotRework" -> "Active(1w)","wafer" -> "W3→hold",  "saga" -> "Initiated","phase" -> "Split"),
    Map("seq" -> "9",  "event" -> "Hold: W3 placed on hold (engineer review)","lotSource" -> "(wait)",   "lotRework" -> "OnHold","wafer" -> "W3=OnHold",  "saga" -> "Committed","phase" -> "Hold"),
    Map("seq" -> "10", "event" -> "Engineer Review (15s) → Decision: PASS",   "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "—",         "saga" -> "—",      "phase" -> "Review"),
    Map("seq" -> "11", "event" -> "Release: W3 hold released",                "lotSource" -> "—",        "lotRework" -> "Active","wafer" -> "W3→Active",  "saga" -> "—",      "phase" -> "Release"),
    Map("seq" -> "12", "event" -> "Merge: Hold Lot → Source Lot",             "lotSource" -> "Active(5w)","lotRework" -> "Empty","wafer" -> "W3→source",  "saga" -> "Completed","phase" -> "Merge"),
    Map("seq" -> "13", "event" -> "Return FOUP to Stocker + Demo Completed",  "lotSource" -> "Sealed",   "lotRework" -> "—",     "wafer" -> "5 Active",  "saga" -> "—",      "phase" -> "Complete"),
  )

  // ===========================================================================
  // Dynamic POR Ledger Generator
  // ===========================================================================

  private def generateRoutingLedger(routing: Por): Seq[Map[String, String]] = {
    val measureAreas = Set("LITHO", "ETCH", "MET")
    var seq = 0
    var prevArea = "STOCKER"
    val buf = Seq.newBuilder[Map[String, String]]

    def add(event: String, lot: String, wafer: String, saga: String, phase: String): Unit = {
      buf += Map("seq" -> seq.toString, "event" -> event,
        "lotSource" -> lot, "lotRework" -> "—", "wafer" -> wafer, "saga" -> saga, "phase" -> phase)
      seq += 1
    }

    add(s"Load FOUP: ${routing.productId} (5 wafers, ${routing.steps.size} steps)", "—", "—", "—", "Load")

    routing.steps.foreach { step =>
      val areaId = step.equipmentArea.areaId
      val equipId = FabFlowEngine.AreaToEquipmentId.getOrElse(areaId, s"$areaId-01")
      val reentry = routing.steps.take(routing.steps.indexOf(step)).count(_.equipmentArea.areaId == areaId)
      val reentryLabel = if (reentry > 0) s" (reentry #$reentry)" else ""

      add(s"Transport: $prevArea → $areaId", "—", "—", "—", "Transport")
      add(s"Arrive: $equipId ($areaId)$reentryLabel", "—", "—", "—", "AtEqp")
      add(s"Process: ${step.recipeId} on $equipId", "—", "(process)", "—", "Process")

      if (measureAreas.contains(areaId)) {
        add(s"Transport: $areaId → MET (CD-SEM)", "—", "—", "—", "Transport")
        add(s"Arrive: CD-SEM-01 (MET)", "—", "—", "—", "AtEqp")
        add(s"Measure: CD-SEM — ${step.recipeId}", "—", "(measure)", "—", "Measure")
        add(s"Classify: Decision Engine → Advance", "—", "PASS/FAIL/SCRAP", "—", "Decide")
      } else {
        add(s"Step Complete: $areaId (no measure, auto-advance)", "—", "—", "—", "Advance")
      }
      prevArea = areaId
    }

    add(s"Transport: $prevArea → STOCKER", "—", "—", "—", "Transport")
    add(s"Seal Lot + Demo Completed: ${routing.productId}", "Sealed", "5 Active", "—", "Complete")

    buf.result()
  }

  /**
   * Query real aggregate entity state by work order ID.
   *
   * Uses the FabDemoViewProjection shared childLotRegistry (populated by the
   * projection handler on LotCreated) to locate child lots by their real UUIDs,
   * falling back to deterministic UUIDs for scenarios run before the registry existed.
   */
  def queryEntityState(workOrderId: String): Future[EntityStateSnapshot] = {
    import net.imadz.application.aggregates.LotProtocol.GetLotState
    import net.imadz.application.projection.FabDemoViewProjection
    import java.util.UUID

    val sourceLotUUID = UUID.nameUUIDFromBytes(s"$workOrderId-source-lot".getBytes)
    val lotRef = sharding.entityRefFor(LotEntityTypeKey, sourceLotUUID.toString)

    // Display key → (registry reason key, deterministic UUID suffix)
    val childLotSpecs = Seq(
      ("rework", "rwk", "rework"),
      ("scrap",  "scrap", "scrap"),
      ("pilot",  "pilot", "pilot"),
      ("sample", "sample", "sample"),
      ("hold",   "hold", "hold")
    )

    lotRef.ask[LotConfirmation](GetLotState(_)).flatMap { lotConf =>
      val childLotFutures: Seq[Future[Option[(String, LotConfirmation)]]] = childLotSpecs.map {
        case (displayKey, reasonKey, detSuffix) =>
          // Prefer real UUID from projection registry, fall back to deterministic
          val registryKey = sourceLotUUID.toString + ":" + reasonKey
          val realId = Option(FabDemoViewProjection.childLotRegistry.get(registryKey))
            .getOrElse(UUID.nameUUIDFromBytes(s"$workOrderId-$detSuffix-lot".getBytes).toString)
          val ref = sharding.entityRefFor(LotEntityTypeKey, realId)
          ref.ask[LotConfirmation](GetLotState(_))
            .map { conf =>
              // Only show child lots that actually contain wafers —
              // empty sealed lots (post-merge) and not-yet-filled lots are hidden.
              if (conf.waferIds.nonEmpty) Some(displayKey -> conf) else None
            }
            .recover { case _ => None }
      }

      Future.sequence(childLotFutures).map { results =>
        EntityStateSnapshot(
          workOrderId = workOrderId,
          lot = lotConf,
          childLots = results.flatten.toMap
        )
      }
    }
  }

  /** Seed all 5 default RouteDefinitions into RoutingRepository (idempotent). */
  def seedDefaultRoutes(): Unit = {
    import net.imadz.application.routing._

    // 1. Basic Rework route
    RouteDefinitionStore.publish(RouteDefinition(
      routeId = "PHOTOCELL-5WAFER", productId = "PHOTOCELL-5WAFER", version = 1,
      name = "Photo Cell 5-Wafer Rework", description = "Basic litho→measure→classify with rework loop",
      nodes = List(
        AtomicStep("n1", "Load FOUP", LoadFoupOp),
        AtomicStep("n2", "Transport→LITHO", TransportOp, Map("from"->"STOCKER","to"->"LITHO")),
        AtomicStep("n3", "At LITHO-01", AtEquipmentOp, Map("area"->"LITHO","equipId"->"LITHO-01")),
        AtomicStep("n4", "TrackIn LITHO", TrackInOp, Map("equipId"->"LITHO-01")),
        AtomicStep("n5", "Run Litho Recipe", RunRecipeOp, Map("equipId"->"LITHO-01","recipeId"->"LITHO-28-001")),
        AtomicStep("n6", "TrackOut LITHO", TrackOutOp, Map("equipId"->"LITHO-01")),
        AtomicStep("n7", "Transport→CDSEM", TransportOp, Map("from"->"LITHO","to"->"CDSEM")),
        AtomicStep("n8", "At CDSEM-01", AtEquipmentOp, Map("area"->"METROLOGY","equipId"->"CDSEM-01")),
        AtomicStep("n9", "TrackIn CDSEM", TrackInOp, Map("equipId"->"CDSEM-01")),
        AtomicStep("n10", "Measure CD", MeasureOp, Map("equipId"->"CDSEM-01")),
        AtomicStep("n11", "TrackOut CDSEM", TrackOutOp, Map("equipId"->"CDSEM-01")),
        AtomicStep("n12", "Classify", ClassifyOp),
        DecisionNode("n13", "All Passed?", MeasurementCondition("cd_nm", WithinRange, 28.0, 34.0, AllWafers)),
        SubProcessRef("n14", "Rework Loop", ReworkLoop, Map("maxReworkCount"->"2","reworkRecipeId"->"REWORK-LITHO-001")),
        AtomicStep("n15", "Transport→Stocker", TransportOp, Map("from"->"CDSEM","to"->"STOCKER")),
        AtomicStep("n16", "Seal Complete", SealCompleteOp)
      ),
      edges = List(
        RouteEdge("e1","n1","n2"), RouteEdge("e2","n2","n3"), RouteEdge("e3","n3","n4"),
        RouteEdge("e4","n4","n5"), RouteEdge("e5","n5","n6"), RouteEdge("e6","n6","n7"),
        RouteEdge("e7","n7","n8"), RouteEdge("e8","n8","n9"), RouteEdge("e9","n9","n10"),
        RouteEdge("e10","n10","n11"), RouteEdge("e11","n11","n12"), RouteEdge("e12","n12","n13"),
        RouteEdge("e13","n13","n15", label = "all PASS"),
        RouteEdge("e14","n13","n14", edgeType = ExceptionFlow, label = "FAIL/BORDERLINE"),
        RouteEdge("e15","n14","n10", label = "rework→re-measure"),
        RouteEdge("e16","n15","n16")
      ),
      ocapRules = ocapRuleStore.getRulesByRoute("PHOTOCELL-5WAFER")
    ))

    // 2. Send-Ahead Pilot
    RouteDefinitionStore.publish(RouteDefinition(
      routeId = "SEND-AHEAD-PILOT", productId = "LOGIC-28NM-A", version = 1,
      name = "Send-Ahead Pilot", description = "Split pilot wafer, verify CD, then process main lot",
      nodes = List(
        AtomicStep("n1","Load FOUP",LoadFoupOp),
        SagaStep("n2","Split Pilot",SagaSplitOp,"pilot",FixedCount(1)),
        SubProcessRef("n3","Pilot Litho+Measure",SendAheadPilot,Map("pilotRecipeId"->"LITHO-28-001")),
        DecisionNode("n4","Pilot CD OK?",MeasurementCondition("cd_nm",WithinRange,28.0,34.0,AllWafers)),
        SagaStep("n5","Merge Pilot Back",SagaMergeOp,"pilot",FixedCount(1)),
        AtomicStep("n6","Transport→LITHO",TransportOp,Map("from"->"STOCKER","to"->"LITHO")),
        AtomicStep("n7","At LITHO-01",AtEquipmentOp,Map("area"->"LITHO","equipId"->"LITHO-01")),
        AtomicStep("n8","TrackIn",TrackInOp,Map("equipId"->"LITHO-01")),
        AtomicStep("n9","Run Litho",RunRecipeOp,Map("equipId"->"LITHO-01","recipeId"->"LITHO-28-001")),
        AtomicStep("n10","TrackOut",TrackOutOp,Map("equipId"->"LITHO-01")),
        AtomicStep("n11","Transport→CDSEM",TransportOp,Map("from"->"LITHO","to"->"CDSEM")),
        AtomicStep("n12","At CDSEM",AtEquipmentOp,Map("area"->"METROLOGY","equipId"->"CDSEM-01")),
        AtomicStep("n13","TrackIn CD",TrackInOp,Map("equipId"->"CDSEM-01")),
        AtomicStep("n14","Measure",MeasureOp,Map("equipId"->"CDSEM-01")),
        AtomicStep("n15","TrackOut CD",TrackOutOp,Map("equipId"->"CDSEM-01")),
        AtomicStep("n16","Classify",ClassifyOp),
        AtomicStep("n17","Transport→Stocker",TransportOp,Map("from"->"CDSEM","to"->"STOCKER")),
        AtomicStep("n18","Seal",SealCompleteOp),
        AtomicStep("n19","Scrap",ScrapWafersOp,Map.empty)
      ),
      edges = List(
        RouteEdge("e1","n1","n2"),RouteEdge("e2","n2","n3"),RouteEdge("e3","n3","n4"),
        RouteEdge("e4","n4","n5",label="pilot PASS"),RouteEdge("e5","n5","n6"),
        RouteEdge("e6","n6","n7"),RouteEdge("e7","n7","n8"),RouteEdge("e8","n8","n9"),
        RouteEdge("e9","n9","n10"),RouteEdge("e10","n10","n11"),RouteEdge("e11","n11","n12"),
        RouteEdge("e12","n12","n13"),RouteEdge("e13","n13","n14"),RouteEdge("e14","n14","n15"),
        RouteEdge("e15","n15","n16"),RouteEdge("e16","n16","n17"),RouteEdge("e17","n17","n18"),
        RouteEdge("e18","n4","n19",edgeType=ExceptionFlow,label="pilot FAIL"),
        RouteEdge("e19","n19","n18")
      )
    ))

    // 3. Scrap Downgrade
    RouteDefinitionStore.publish(RouteDefinition(
      routeId = "SCRAP-DOWNGRADE", productId = "LOGIC-28NM-A", version = 1,
      name = "Scrap Downgrade", description = "Litho→measure: classify SCRAP wafers→split to scrap lot",
      nodes = List(
        AtomicStep("n1","Load FOUP",LoadFoupOp),
        AtomicStep("n2","Transport→LITHO",TransportOp,Map("from"->"STOCKER","to"->"LITHO")),
        AtomicStep("n3","At LITHO-01",AtEquipmentOp,Map("area"->"LITHO","equipId"->"LITHO-01")),
        AtomicStep("n4","TrackIn",TrackInOp,Map("equipId"->"LITHO-01")),
        AtomicStep("n5","Run Litho",RunRecipeOp,Map("equipId"->"LITHO-01","recipeId"->"LITHO-28-001")),
        AtomicStep("n6","TrackOut",TrackOutOp,Map("equipId"->"LITHO-01")),
        AtomicStep("n7","Transport→CDSEM",TransportOp,Map("from"->"LITHO","to"->"CDSEM")),
        AtomicStep("n8","At CDSEM",AtEquipmentOp,Map("area"->"METROLOGY","equipId"->"CDSEM-01")),
        AtomicStep("n9","TrackIn CD",TrackInOp,Map("equipId"->"CDSEM-01")),
        AtomicStep("n10","Measure",MeasureOp,Map("equipId"->"CDSEM-01")),
        AtomicStep("n11","TrackOut CD",TrackOutOp,Map("equipId"->"CDSEM-01")),
        AtomicStep("n12","Classify",ClassifyOp),
        SagaStep("n13","Split Scrap",SagaSplitOp,"scrap",FixedCount(1)),
        AtomicStep("n14","Scrap Wafers",ScrapWafersOp,Map.empty),
        AtomicStep("n15","Transport→Stocker",TransportOp,Map("from"->"CDSEM","to"->"STOCKER")),
        AtomicStep("n16","Seal",SealCompleteOp)
      ),
      edges = List(
        RouteEdge("e1","n1","n2"),RouteEdge("e2","n2","n3"),RouteEdge("e3","n3","n4"),
        RouteEdge("e4","n4","n5"),RouteEdge("e5","n5","n6"),RouteEdge("e6","n6","n7"),
        RouteEdge("e7","n7","n8"),RouteEdge("e8","n8","n9"),RouteEdge("e9","n9","n10"),
        RouteEdge("e10","n10","n11"),RouteEdge("e11","n11","n12"),RouteEdge("e12","n12","n13"),
        RouteEdge("e13","n13","n14"),RouteEdge("e14","n14","n15"),RouteEdge("e15","n15","n16")
      )
    ))

    // 4. Sampling
    RouteDefinitionStore.publish(RouteDefinition(
      routeId = "SAMPLING", productId = "LOGIC-28NM-A", version = 1,
      name = "Sampling Demo", description = "Sample wafers→measure→classify→merge back",
      nodes = List(
        AtomicStep("n1","Load FOUP",LoadFoupOp),
        SagaStep("n2","Split Sample",SagaSplitOp,"sample",FixedCount(1)),
        AtomicStep("n3","Transport→CDSEM",TransportOp,Map("from"->"STOCKER","to"->"CDSEM")),
        AtomicStep("n4","At CDSEM",AtEquipmentOp,Map("area"->"METROLOGY","equipId"->"CDSEM-01")),
        AtomicStep("n5","TrackIn",TrackInOp,Map("equipId"->"CDSEM-01")),
        AtomicStep("n6","Measure",MeasureOp,Map("equipId"->"CDSEM-01")),
        AtomicStep("n7","TrackOut",TrackOutOp,Map("equipId"->"CDSEM-01")),
        AtomicStep("n8","Classify",ClassifyOp),
        SagaStep("n9","Merge Sample",SagaMergeOp,"sample",FixedCount(1)),
        AtomicStep("n10","Transport→Stocker",TransportOp,Map("from"->"CDSEM","to"->"STOCKER")),
        AtomicStep("n11","Seal",SealCompleteOp)
      ),
      edges = List(
        RouteEdge("e1","n1","n2"),RouteEdge("e2","n2","n3"),RouteEdge("e3","n3","n4"),
        RouteEdge("e4","n4","n5"),RouteEdge("e5","n5","n6"),RouteEdge("e6","n6","n7"),
        RouteEdge("e7","n7","n8"),RouteEdge("e8","n8","n9"),RouteEdge("e9","n9","n10"),
        RouteEdge("e10","n10","n11")
      )
    ))

    // 5. Hold-Release
    RouteDefinitionStore.publish(RouteDefinition(
      routeId = "HOLD-RELEASE", productId = "LOGIC-28NM-A", version = 1,
      name = "Hold & Release", description = "Classify→hold borderline wafers→engineer review→release",
      nodes = List(
        AtomicStep("n1","Load FOUP",LoadFoupOp),
        AtomicStep("n2","Transport→LITHO",TransportOp,Map("from"->"STOCKER","to"->"LITHO")),
        AtomicStep("n3","At LITHO-01",AtEquipmentOp,Map("area"->"LITHO","equipId"->"LITHO-01")),
        AtomicStep("n4","TrackIn",TrackInOp,Map("equipId"->"LITHO-01")),
        AtomicStep("n5","Run Litho",RunRecipeOp,Map("equipId"->"LITHO-01","recipeId"->"LITHO-28-001")),
        AtomicStep("n6","TrackOut",TrackOutOp,Map("equipId"->"LITHO-01")),
        AtomicStep("n7","Transport→CDSEM",TransportOp,Map("from"->"LITHO","to"->"CDSEM")),
        AtomicStep("n8","At CDSEM",AtEquipmentOp,Map("area"->"METROLOGY","equipId"->"CDSEM-01")),
        AtomicStep("n9","TrackIn CD",TrackInOp,Map("equipId"->"CDSEM-01")),
        AtomicStep("n10","Measure",MeasureOp,Map("equipId"->"CDSEM-01")),
        AtomicStep("n11","TrackOut CD",TrackOutOp,Map("equipId"->"CDSEM-01")),
        AtomicStep("n12","Classify",ClassifyOp),
        SagaStep("n13","Split Hold",SagaSplitOp,"hold",FixedCount(1)),
        AtomicStep("n14","Hold Wafers",HoldWafersOp),
        WaitNode("n15","Engineer Review",15000L),
        AtomicStep("n16","Release Wafers",ReleaseWafersOp),
        DecisionNode("n17","Review Approved?",MeasurementCondition("cd_nm",WithinRange,28.0,34.0,AllWafers)),
        SagaStep("n18","Merge Hold Back",SagaMergeOp,"hold",FixedCount(1)),
        AtomicStep("n19","Transport→Stocker",TransportOp,Map("from"->"CDSEM","to"->"STOCKER")),
        AtomicStep("n20","Scrap",ScrapWafersOp,Map.empty),
        AtomicStep("n21","Seal",SealCompleteOp)
      ),
      edges = List(
        RouteEdge("e1","n1","n2"),RouteEdge("e2","n2","n3"),RouteEdge("e3","n3","n4"),
        RouteEdge("e4","n4","n5"),RouteEdge("e5","n5","n6"),RouteEdge("e6","n6","n7"),
        RouteEdge("e7","n7","n8"),RouteEdge("e8","n8","n9"),RouteEdge("e9","n9","n10"),
        RouteEdge("e10","n10","n11"),RouteEdge("e11","n11","n12"),RouteEdge("e12","n12","n13"),
        RouteEdge("e13","n13","n14"),RouteEdge("e14","n14","n15"),RouteEdge("e15","n15","n16"),
        RouteEdge("e16","n16","n17"),RouteEdge("e17","n17","n18",label="approved"),
        RouteEdge("e18","n18","n19"),RouteEdge("e19","n19","n21"),
        RouteEdge("e20","n17","n20",edgeType=ExceptionFlow,label="rejected"),
        RouteEdge("e21","n20","n19")
      )
    ))
  }

  case class EntityStateSnapshot(
    workOrderId: String,
    lot: LotConfirmation,
    childLots: Map[String, LotConfirmation]
  )

  // ===========================================================================
  // M3.5 Self-Healing Demo
  // ===========================================================================

  /** Map M3.5 scenario type to internal scenario ID and OCAP rules. */
  private def resolveM35Scenario(scenarioType: String): (String, List[OcapRuleDefinition]) = {
    val allRules = ocapRuleStore.getRules
    scenarioType match {
      case "send-ahead-ocap"      => ("send-ahead-pilot", allRules.filter(r => r.routeId == "SEND-AHEAD-PILOT" || r.routeId.isEmpty))
      case "multi-workorder-chaos" => ("photo-cell-5wafer", allRules.filter(r => r.routeId == "PHOTOCELL-5WAFER" || r.routeId.isEmpty))
      case _                       => ("photo-cell-5wafer", allRules.filter(r => r.routeId == "PHOTOCELL-5WAFER" || r.routeId.isEmpty))
    }
  }

  /** Resolve M3.5 scenario to OCAP-enhanced PipelineStages. */
  private def m35StageResolver(scenarioId: String, ocapRules: List[OcapRuleDefinition]): Seq[PipelineStage] = scenarioId match {
    case "send-ahead-pilot" => FabScenarioPipeline.m35SendAheadStages(ocapRules)
    case _                  => FabScenarioPipeline.m35BasicStages(ocapRules)
  }

  /** Build scenario profile for "OCAP Rework + Crash" with guaranteed OCAP firing. */
  private def buildOcapReworkCrashScenario(faultProbability: Double): (FabSimulationScenario, Seq[PipelineStage]) = {
    val baseScenario = StandardScenarios.photoCell5Wafer
    // Guarantee at least 1 borderline CD (triggers OCAP rule OCAP-001: cd_nm BETWEEN 34.0 AND 36.0)
    // WAFER-3 gets BORDERLINE outcome with CD ~34.5nm
    // WAFER-5 gets SCRAP to show scrap bin
    val waferIds = baseScenario.waferIds
    val guaranteedOutcomes = Map(
      "WAFER-1" -> "PASS",
      "WAFER-2" -> "PASS",
      "WAFER-3" -> "BORDERLINE",  // ~34.5nm → triggers OCAP Rework rule
      "WAFER-4" -> "PASS",
      "WAFER-5" -> "FAIL"         // triggers rework
    )
    val adjustedCdSem = baseScenario.cdSemDetail.copy(
      waferOutcomes = guaranteedOutcomes
    )
    val adjustedLitho = baseScenario.lithoDetail.copy(
      hardwareFaultRate = faultProbability
    )
    val scenario = baseScenario.copy(
      cdSemDetail = adjustedCdSem,
      lithoDetail = adjustedLitho,
      litho = baseScenario.litho.copy(faultProbability = faultProbability),
      cdSem = baseScenario.cdSem.copy(faultProbability = faultProbability)
    )
    val rules = ocapRuleStore.getRules.filter(r => r.routeId == "PHOTOCELL-5WAFER" || r.routeId.isEmpty)
    val stages = FabScenarioPipeline.m35BasicStages(rules)
    (scenario, stages)
  }

  /** Build "Send-Ahead with OCAP" scenario. */
  private def buildSendAheadOcapScenario(faultProbability: Double): (FabSimulationScenario, Seq[PipelineStage]) = {
    val baseScenario = StandardScenarios.sendAheadPilot
    // Pilot wafer gets borderline CD to trigger OCAP notify rule
    val waferIds = baseScenario.waferIds
    val guaranteedOutcomes = Map(
      "PILOT-WAFER-1" -> "BORDERLINE", // ~35nm → triggers OCAP Notify
      "PILOT-WAFER-2" -> "PASS",
      "PILOT-WAFER-3" -> "PASS",
      "PILOT-WAFER-4" -> "PASS",
      "PILOT-WAFER-5" -> "PASS"
    )
    val adjustedCdSem = baseScenario.cdSemDetail.copy(
      waferOutcomes = guaranteedOutcomes
    )
    val scenario = baseScenario.copy(
      cdSemDetail = adjustedCdSem,
      litho = baseScenario.litho.copy(faultProbability = faultProbability),
      cdSem = baseScenario.cdSem.copy(faultProbability = faultProbability)
    )
    val rules = ocapRuleStore.getRules.filter(r => r.routeId == "SEND-AHEAD-PILOT" || r.routeId.isEmpty)
    val stages = FabScenarioPipeline.m35SendAheadStages(rules)
    (scenario, stages)
  }

  /** Build "Multi-WorkOrder Chaos" scenario — returns base config + stage list for one of 3 concurrent WOs. */
  private def buildMultiWorkOrderChaosScenario(faultProbability: Double): (FabSimulationScenario, Seq[PipelineStage]) = {
    val baseScenario = StandardScenarios.photoCell5Wafer
    // High fault rate + mixed outcomes to guarantee interesting behavior
    val waferIds = baseScenario.waferIds
    val guaranteedOutcomes = Map(
      "WAFER-1" -> "PASS",
      "WAFER-2" -> "PASS",
      "WAFER-3" -> "BORDERLINE",
      "WAFER-4" -> "FAIL",
      "WAFER-5" -> "SCRAP"
    )
    val adjustedCdSem = baseScenario.cdSemDetail.copy(
      waferOutcomes = guaranteedOutcomes
    )
    val scenario = baseScenario.copy(
      cdSemDetail = adjustedCdSem,
      litho = baseScenario.litho.copy(faultProbability = faultProbability),
      cdSem = baseScenario.cdSem.copy(faultProbability = faultProbability)
    )
    val rules = ocapRuleStore.getRules.filter(r => r.routeId == "PHOTOCELL-5WAFER" || r.routeId.isEmpty)
    val stages = FabScenarioPipeline.m35ChaosStages(rules)
    (scenario, stages)
  }

  /** Build a FabDemoContext for M3.5 demo recovery from deterministic IDs. */
  private def m35ContextFactory(scenarioId: String, workOrderId: String): FabDemoContext = {
    val ocapRules = forM35ContextOcapRules(scenarioId)
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
    val pilotLotId: Id = UUID.nameUUIDFromBytes(s"$workOrderId-pilot-lot".getBytes)
    val sampleLotId: Id = UUID.nameUUIDFromBytes(s"$workOrderId-sample-lot".getBytes)
    val holdLotId: Id   = UUID.nameUUIDFromBytes(s"$workOrderId-hold-lot".getBytes)

    val lotRef = sharding.entityRefFor(LotEntityTypeKey, sourceLotId.toString)
    val reworkLotRef = sharding.entityRefFor(LotEntityTypeKey, reworkLotId.toString)
    val scrapLotRef = sharding.entityRefFor(LotEntityTypeKey, scrapLotId.toString)
    val pilotLotRef = sharding.entityRefFor(LotEntityTypeKey, pilotLotId.toString)
    val sampleLotRef = sharding.entityRefFor(LotEntityTypeKey, sampleLotId.toString)
    val holdLotRef = sharding.entityRefFor(LotEntityTypeKey, holdLotId.toString)

    val sagaTxFn: (Id, Id, Set[Id], Set[String], Option[Id]) => Future[FabSagaConfirmation] =
      (srcId, tgtId, wids, names, existingTxId) => fabSagaService.transferWafers(srcId, tgtId, wids, names, existingTxId)

    // Reuse shared adapter so simulator refs survive pipeline crashes
    val adapter = m35Adapter.getOrElse(new ActorEquipmentAdapter())
    val ignoreLotReply = system.ignoreRef[LotConfirmation]
    val pub = systemWidePublisher.getOrElse(publisherRegistry.getOrDefault(workOrderId, _ => ()).asInstanceOf[FabSimulationEvent => Unit])

    FabDemoContext(
      scenario = scenario,
      foupId = s"FOUP-${scenario.scenarioId}",
      lotRef = lotRef,
      reworkLotRef = reworkLotRef,
      waferUUIDs = waferUUIDs,
      sourceLotId = sourceLotId,
      reworkLotId = reworkLotId,
      adapter = adapter,
      publisher = pub,
      ignoreLotReply = ignoreLotReply,
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
      ocapRules = ocapRules,
      faultProbability = currentFaultProbability
    )
  }

  /** Resolve OCAP rules for M3.5 context by scenario. */
  private def forM35ContextOcapRules(scenarioId: String): List[OcapRuleDefinition] = {
    val routeId = scenarioId match {
      case "send-ahead-pilot" => "SEND-AHEAD-PILOT"
      case _                  => "PHOTOCELL-5WAFER"
    }
    ocapRuleStore.getRules.filter(r => r.routeId == routeId || r.routeId.isEmpty)
  }

  /** Build a FabDemoState for M3.5 demo recovery. */
  private def m35StateFactory(workOrderId: String): FabDemoState = {
    // Reconstruct initial state with wafer info — wafers are re-created from entities
    FabDemoState(wafers = Map.empty)
  }

  /** Start the M3.5 self-healing demo.
    * Uses FabPipelineExecutionActor for crash-resilient EventSourced execution.
    *
    * @param scenarioType   "ocap-rework-crash" | "send-ahead-ocap" | "multi-workorder-chaos"
    * @param faultProbability  0.0–1.0 fault probability (default 0.2 for 20%)
    * @param publisher     WebSocket event publisher
    * @return WorkOrderConfirmation with the started work order */
  def startM35Demo(
    scenarioType: String,
    faultProbability: Double,
    publisher: FabSimulationEvent => Unit
  ): Future[WorkOrderConfirmation] = {
    net.imadz.application.projection.FabDemoViewHandler.resetAll()
    net.imadz.application.projection.FabDemoViewProjection.resetChildLotRegistry()
    net.imadz.application.actor.EquipmentAreaActor.Registry.resetAll()
    currentFaultProbability = faultProbability

    scenarioType match {
      case "multi-workorder-chaos" => startM35MultiWorkOrderChaos(faultProbability, publisher)
      case _                       => startM35SingleWorkOrder(scenarioType, faultProbability, publisher)
    }
  }

  /** Start a single work order M3.5 demo (ocap-rework-crash or send-ahead-ocap). */
  private def startM35SingleWorkOrder(
    scenarioType: String,
    faultProbability: Double,
    publisher: FabSimulationEvent => Unit
  ): Future[WorkOrderConfirmation] = {
    val (scenarioId, ocapRules) = resolveM35Scenario(scenarioType)
    val (scenario, stages) = scenarioType match {
      case "send-ahead-ocap" => buildSendAheadOcapScenario(faultProbability)
      case _                 => buildOcapReworkCrashScenario(faultProbability)
    }
    val workOrderId = UUID.randomUUID().toString

    publisherRegistry.put(workOrderId, publisher.asInstanceOf[Any => Unit])

    // Publish a timeline snapshot to initialize the UI
    publisher(PipelineTimelineSnapshot(
      workOrderId = workOrderId,
      totalPhases = stages.size,
      completedPhases = 0,
      currentPhase = Some("Load FOUP"),
      currentPhaseIndex = 0,
      failedPhases = Seq.empty,
      recoveredPhases = Seq.empty,
      ocapTriggers = 0
    ))

    // Create WorkOrder aggregate
    val workOrderRef = sharding.entityRefFor(WorkOrderAggregate.WorkOrderEntityTypeKey, workOrderId)
    workOrderRef.ask[WorkOrderConfirmation](replyTo => CreateWorkOrder(scenarioId, scenario.waferIds, routeRef = None, replyTo = replyTo))
      .map { confirmation =>
        // Create shared equipment adapter and spawn simulators with fault probability
        val adapter = new ActorEquipmentAdapter()
        m35Adapter = Some(adapter)
        spawnSimulators(scenario, adapter, publisher)

        // Build initial context and state (reuses shared adapter via m35Adapter)
        val ctx = m35ContextFactory(scenarioId, workOrderId)
        val initialState = FabDemoState(
          wafers = scenario.waferIds.map(wid => wid -> WaferInfo(wid)).toMap
        )

        // Publish DemoStarted event
        publisher(DemoStarted(scenarioId, scenario.name, scenario.lotSize, scenario.waferIds))

        // Create source lot aggregate (idempotent)
        val waferUUIDs: Map[String, Id] = scenario.waferIds.map { wid =>
          wid -> UUID.nameUUIDFromBytes(s"$workOrderId-$wid".getBytes)
        }.toMap
        val sourceLotId: Id = UUID.nameUUIDFromBytes(s"$workOrderId-source-lot".getBytes)
        val lotRef = sharding.entityRefFor(LotEntityTypeKey, sourceLotId.toString)

        lotRef.ask[LotConfirmation](ref => CreateLot(s"FAB-$workOrderId", waferUUIDs.map(_.swap), ref, workOrderId = Some(workOrderId)))

        // Send StartExecution via ask — wait for Accepted before scheduling crash
        // to guarantee the actor is in Executing state (not Idle) when StopPipeline arrives.
        // NOTE: StartExecution.scenarioId is the CRASH-RECOVERY key — it must resolve, via
        // ApplicationBootstrap.pipelineStageResolver, to the SAME stage list as `stages`.
        // The legacy route id ("photo-cell-5wafer") resolves to a different (M3.0) list,
        // which made post-crash recovery resume an empty queue and instantly report success.
        val pipelineRef = sharding.entityRefFor(FabPipelineExecutionActor.EntityKey, workOrderId)
        pipelineRef.ask[FabPipelineExecutionActor.ExecutionReply](ref =>
          FabPipelineExecutionActor.StartExecution(
            scenarioId = scenarioType,
            workOrderId = workOrderId,
            initialState = initialState,
            stages = stages,
            ctx = ctx,
            replyTo = ref
          )
        ).foreach {
          case FabPipelineExecutionActor.Accepted =>
            scheduleAutoCrash(workOrderId, publisher, delaySeconds = 15)
          case FabPipelineExecutionActor.Rejected(reason) =>
            system.log.warn(s"StartExecution rejected for $workOrderId: $reason")
        }(ec)

        confirmation
      }(ec)
  }

  /** Start 3 concurrent work orders for the Multi-WorkOrder Chaos scenario. */
  private def startM35MultiWorkOrderChaos(
    faultProbability: Double,
    publisher: FabSimulationEvent => Unit
  ): Future[WorkOrderConfirmation] = {
    // Use the chaos scenario config for all 3 WOs
    val (baseScenario, stages) = buildMultiWorkOrderChaosScenario(faultProbability)
    val rulePrefixes = Seq("WO-ALPHA", "WO-BRAVO", "WO-CHARLIE")
    val productPrefixes = Seq("ALPHA-01", "BRAVO-01", "CHARLIE-01")

    // Run 3 work orders concurrently
    rulePrefixes.foreach { prefix =>
      val workOrderId = prefix + "-" + UUID.randomUUID().toString.take(8)
      val scenarioId = "photo-cell-5wafer"

      publisherRegistry.put(workOrderId, publisher.asInstanceOf[Any => Unit])

      val workOrderRef = sharding.entityRefFor(WorkOrderAggregate.WorkOrderEntityTypeKey, workOrderId)
      workOrderRef.ask[WorkOrderConfirmation](replyTo =>
        CreateWorkOrder(scenarioId, baseScenario.waferIds, routeRef = None, replyTo = replyTo)
      ).foreach { _ =>

        // Publish DemoStarted event
        publisher(DemoStarted(s"chaos-$prefix", s"Chaos $prefix", baseScenario.lotSize, baseScenario.waferIds))

        // Publish a timeline snapshot to initialize the UI
        publisher(PipelineTimelineSnapshot(
          workOrderId = workOrderId,
          totalPhases = stages.size,
          completedPhases = 0,
          currentPhase = Some("Load FOUP"),
          currentPhaseIndex = 0,
          failedPhases = Seq.empty,
          recoveredPhases = Seq.empty,
          ocapTriggers = 0
        ))

        // Create lot and start pipeline
        val waferUUIDs: Map[String, Id] = baseScenario.waferIds.map { wid =>
          wid -> UUID.nameUUIDFromBytes(s"$workOrderId-$wid".getBytes)
        }.toMap
        val sourceLotId: Id = UUID.nameUUIDFromBytes(s"$workOrderId-source-lot".getBytes)
        val lotRef = sharding.entityRefFor(LotEntityTypeKey, sourceLotId.toString)

        lotRef.ask[LotConfirmation](ref => CreateLot(s"FAB-$workOrderId", waferUUIDs.map(_.swap), ref, workOrderId = Some(workOrderId)))

        val ctx = m35ContextFactory(scenarioId, workOrderId)
        val initialState = FabDemoState(
          wafers = baseScenario.waferIds.map(wid => wid -> WaferInfo(wid)).toMap
        )

        val pipelineRef = sharding.entityRefFor(FabPipelineExecutionActor.EntityKey, workOrderId)
        pipelineRef.ask[FabPipelineExecutionActor.ExecutionReply](ref =>
          FabPipelineExecutionActor.StartExecution(
            scenarioId = "multi-workorder-chaos", // crash-recovery key — must match pipelineStageResolver
            workOrderId = workOrderId,
            initialState = initialState,
            stages = stages,
            ctx = ctx,
            replyTo = ref
          )
        ).foreach {
          case FabPipelineExecutionActor.Accepted =>
            // Schedule crash for one of the 3 WOs (staggered) — only after actor confirms Executing state
            if (prefix == rulePrefixes.last) {
              scheduleAutoCrash(workOrderId, publisher, delaySeconds = 25)
            }
          case FabPipelineExecutionActor.Rejected(reason) =>
            system.log.warn(s"StartExecution rejected for $workOrderId: $reason")
        }(ec)
      }(ec)
    }

    // Return the first work order as primary
    val primaryWoId = rulePrefixes.head + "-" + UUID.randomUUID().toString.take(8)
    Future.successful(WorkOrderConfirmation(primaryWoId, "STARTED"))
  }

  /** Schedule automatic crash injection mid-pipeline. */
  private def scheduleAutoCrash(workOrderId: String, publisher: FabSimulationEvent => Unit, delaySeconds: Int): Unit = {
    if (delaySeconds > 0) {
      classicSystem.scheduler.scheduleOnce(
        delaySeconds.seconds,
        new Runnable {
          def run(): Unit = {
            try {
              val entityRef = sharding.entityRefFor(FabPipelineExecutionActor.EntityKey, workOrderId)
              publisher(FaultInjected(workOrderId, "FabPipelineExecutionActor", "actor_crash", "pipeline",
                resolved = false, resolution = Some(s"Auto-scheduled crash at ${delaySeconds}s")))
              entityRef ! FabPipelineExecutionActor.StopPipeline(workOrderId)
              publisher(RecoveryEvent(workOrderId, "CRASH_DETECTED", 0, 0,
                System.currentTimeMillis(),
                s"Auto-scheduled crash injected at ${delaySeconds}s"))
            } catch {
              case e: Exception =>
                publisher(RecoveryEvent(workOrderId, "CRASH_DETECTED", 0, 0,
                  System.currentTimeMillis(),
                  s"Auto-crash failed: ${e.getMessage}"))
            }
          }
        }
      )(classicSystem.dispatcher)
    }
  }

  // Keep the original m35StageResolver for backward compatibility with controller
  // Only used for timeline snapshot count, actual pipeline uses dynamicStageResolver
  private def m35StageCount(scenarioId: String): Int = scenarioId match {
    case "send-ahead-pilot" => FabScenarioPipeline.m35SendAheadStages(Nil).size
    case _                  => FabScenarioPipeline.m35BasicStages(Nil).size
  }

  /** Inject a crash into the pipeline for the given workOrderId.
    * Sends StopPipeline to the FabPipelineExecutionActor entity,
    * which throws a RuntimeException → actor stops → sharding restarts → RecoveryCompleted fires. */
  def injectCrash(workOrderId: String, publisher: FabSimulationEvent => Unit): Future[Boolean] = {
    implicit val disp = classicSystem.dispatcher
    try {
      val entityRef = sharding.entityRefFor(FabPipelineExecutionActor.EntityKey, workOrderId)
      // Send StopPipeline — the actor will throw, causing sharding to restart it
      entityRef ! FabPipelineExecutionActor.StopPipeline(workOrderId)
      publisher(FaultInjected(workOrderId, "FabPipelineExecutionActor", "actor_crash", "pipeline", resolved = false, Some("Crash injected via StopPipeline")))
      Future.successful(true)
    } catch {
      case e: Exception =>
        publisher(RecoveryEvent(workOrderId, "CRASH_DETECTED", 0, 0, System.currentTimeMillis(),
          s"Crash injection failed: ${e.getMessage}"))
        Future.successful(false)
    }
  }

  /** Update fault probability for equipment simulators mid-demo. */
  def updateFaultProbability(probability: Double): Unit = {
    currentFaultProbability = probability
  }

  /** Return OCAP rules formatted for REST JSON response. */
  def getOcapRulesForM35: Seq[Map[String, Any]] = {
    ocapRuleStore.getRules.map { rule =>
      Map(
        "ruleId" -> rule.ruleId,
        "name" -> rule.name,
        "priority" -> rule.priority,
        "actionType" -> (rule.actionPlan match {
          case _: net.imadz.domain.routing.OcapHold         => "HOLD"
          case _: net.imadz.domain.routing.OcapRework       => "REWORK"
          case _: net.imadz.domain.routing.OcapScrap        => "SCRAP"
          case _: net.imadz.domain.routing.OcapNotify       => "NOTIFY"
          case _: net.imadz.domain.routing.OcapAdjustRecipe => "ADJUST_RECIPE"
          case _: net.imadz.domain.routing.OcapComposite    => "COMPOSITE"
        }),
        "condition" -> rule.triggerCondition.toString,
        "maxTriggersPerLot" -> rule.maxTriggersPerLot
      )
    }
  }

  /** Return recovery status for a work order. For P1, returns a stub response. */
  def getRecoveryStatus(workOrderId: String): Map[String, Any] = {
    Map(
      "workOrderId" -> workOrderId,
      "status" -> "IDLE",
      "recoveryCount" -> 0,
      "lastRecoveryTimeMs" -> 0,
      "phasesSkipped" -> 0,
      "eventsReplayed" -> 0
    )
  }

  /** Return fault history for a work order. For P1, returns a stub response. */
  def getFaultHistory(workOrderId: String): Seq[Map[String, Any]] = {
    Seq.empty
  }
}
