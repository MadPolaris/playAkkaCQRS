package net.imadz.fab.service

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
import net.imadz.fab.chain.{FabDemoPipeline, FabFlowEngine, FabScenarioPipeline}
import net.imadz.fab.model.FabExecutionModel.{FabDemoContext, FabDemoState, WaferInfo}
import net.imadz.fab.events.{DemoStarted, FabSimulationEvent}
import net.imadz.fab.model.{Por, PorRepository}
import net.imadz.fab.protocol.ActorEquipmentAdapter
import net.imadz.fab.routing.{RouteCompiler, RoutingRepository}
import net.imadz.fab.scenario.{FabSimulationScenario, StandardScenarios}
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
  fabSagaService: FabSagaService
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

  def setSystemWidePublisher(publisher: FabSimulationEvent => Unit): Unit = {
    systemWidePublisher = Some(publisher)
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

    val sagaTxFn: (Id, Id, Set[Id], Set[String]) => Future[FabSagaConfirmation] =
      (srcId, tgtId, wids, names) => fabSagaService.transferWafers(srcId, tgtId, wids, names)

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
      childLotRefs = Map("scrap" -> scrapLotRef),
      childLotIds = Map("scrap" -> scrapLotId)
    )

    val initialState = FabDemoState(
      wafers = waferIds.map(wid => wid -> WaferInfo(wid)).toMap
    )

    publisher(DemoStarted(productId, routing.productId, waferIds.size, waferIds))

    val pipelineFn = FabFlowEngine.runRouting(routing, FabFlowEngine.DefaultDecisionConfig) _

    // Create entities (idempotent) then run pipeline
    // Child lots (rework, scrap) are created lazily inside the pipeline's saga split/scrap stages.
    for {
      _ <- lotRef.ask[LotConfirmation](ref => CreateLot(s"FAB-$workOrderId", waferUUIDs.map(_.swap), ref, workOrderId = Some(workOrderId)))
      _ = spawnDynamicSimulators(routing, adapter, publisher, waferIds)
      result <- pipelineFn(initialState, ctx)
    } yield result
  }

  /** Execute a work order from a RouteDefinition (Route Browser "Start" path).
   * Compiles the RouteDefinition to PipelineStages, creates entities, and runs them. */
  private def runFromRoute(
    workOrderId: String, productId: String, routeDef: net.imadz.fab.routing.RouteDefinition,
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

    val sagaTxFn: (Id, Id, Set[Id], Set[String]) => Future[FabSagaConfirmation] =
      (srcId, tgtId, wids, names) => fabSagaService.transferWafers(srcId, tgtId, wids, names)

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
        "scrap" -> scrapLotRef
      ),
      childLotIds = Map(
        "pilot" -> pilotLotId,
        "sample" -> sampleLotId,
        "hold" -> holdLotId,
        "scrap" -> scrapLotId
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
      result <- FabScenarioPipeline.runStages(stages, initialState, ctx)
    } yield result
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

    publisher(net.imadz.fab.events.EquipmentStateChanged("LITHO-01", "LITHO", "Idle", None))
    publisher(net.imadz.fab.events.EquipmentStateChanged("CDSEM-01", "METROLOGY", "Idle", None))
    publisher(net.imadz.fab.events.EquipmentStateChanged("STOCKER-01", "STOCKER", "Idle", None))
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
      case _                   => StandardScenarios.photoCell5Wafer
    }
    val isRework = scenarioId == "photo-cell-5wafer"

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

    val sagaTxFn: (Id, Id, Set[Id], Set[String]) => Future[FabSagaConfirmation] =
      (srcId, tgtId, wids, names) => fabSagaService.transferWafers(srcId, tgtId, wids, names)

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
        "scrap" -> scrapLotRef
      ),
      childLotIds = Map(
        "pilot" -> pilotLotId,
        "sample" -> sampleLotId,
        "hold" -> holdLotId,
        "scrap" -> scrapLotId
      )
    )

    val initialState = FabDemoState(
      wafers = scenario.waferIds.map(wid => wid -> WaferInfo(wid)).toMap
    )

    publisher(DemoStarted(scenario.scenarioId, scenario.name, scenario.lotSize, scenario.waferIds))
    spawnSimulators(scenario, adapter, publisher)

    // Child lots are created lazily inside each pipeline's sagaSplit stage,
    // not upfront — avoids empty child lots appearing in the UI before split.

    val pipelineFn = if (isRework) FabDemoPipeline.runPipeline _ else FabScenarioPipeline.runPipeline _

    for {
      _ <- lotRef.ask[LotConfirmation](ref => CreateLot(s"FAB-$workOrderId", waferUUIDs.map(_.swap), ref, workOrderId = Some(workOrderId)))
      result <- pipelineFn(initialState, ctx)
    } yield result
  }

  /**
   * Start a demo scenario using M2.5+ Chain-aligned FabChainExecutor.
   * Creates EventSourced Lot + Wafer aggregates, wires FabSagaService for TCC split/merge,
   * then runs the 11-stage pipeline via FabChainExecutor (EventSourcedBehavior).
   */
  def startDemo(scenarioId: String, publisher: FabSimulationEvent => Unit): Future[WorkOrderConfirmation] = {
    net.imadz.fab.projection.FabDemoViewHandler.resetAll()
    net.imadz.fab.projection.FabDemoViewProjection.resetChildLotRegistry()
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
    scenario: net.imadz.fab.scenario.FabSimulationScenario,
    adapter: ActorEquipmentAdapter,
    publisher: FabSimulationEvent => Unit
  ): Unit = {
    // Spawn equipment simulators and register with adapter
    import net.imadz.fab.simulation._
    val lithoActor = system.systemActorOf(
      new LithographySimulator(scenario.lithoDetail)(scenario.litho),
      s"litho-${scenario.scenarioId}-${System.currentTimeMillis()}"
    )
    val cdSemActor = system.systemActorOf(
      new CdSemSimulator(scenario.cdSemDetail)(scenario.cdSem),
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

    publisher(net.imadz.fab.events.EquipmentStateChanged(scenario.litho.equipmentId, "LITHO", "Idle", None))
    publisher(net.imadz.fab.events.EquipmentStateChanged(scenario.cdSem.equipmentId, "METROLOGY", "Idle", None))
    publisher(net.imadz.fab.events.EquipmentStateChanged(scenario.stocker.equipmentId, "STOCKER", "Idle", None))
  }

  /**
   * Start a demo using dynamic ProductRouting instead of a static scenario.
   *
   * Looks up the ProductRouting by productId, creates a 5-wafer demo lot,
   * spawns simulators for all equipment areas used in the routing,
   * then runs the dynamic FabFlowEngine via FabChainExecutor.
   */
  def startDemoWithProduct(productId: String, publisher: FabSimulationEvent => Unit): Future[WorkOrderConfirmation] = {
    net.imadz.fab.projection.FabDemoViewHandler.resetAll()
    net.imadz.fab.projection.FabDemoViewProjection.resetChildLotRegistry()
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
    net.imadz.fab.projection.FabDemoViewHandler.resetAll()
    net.imadz.fab.projection.FabDemoViewProjection.resetChildLotRegistry()
    val routeDef = RoutingRepository.getLatest(routeId)
      .getOrElse(throw new IllegalArgumentException(s"Unknown route: $routeId"))
    // Register in PorRepository so it also appears in the Start dropdown
    PorRepository.register(Por(
      productId = routeId,
      steps = routeDef.nodes.collect { case net.imadz.fab.routing.AtomicStep(_, _, op, _) =>
        net.imadz.fab.model.PorStep(
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
    val areaIds = routing.steps.map(_.equipmentArea.areaId).distinct
    val equipIds = areaIds.flatMap(aid => FabFlowEngine.AreaToEquipmentId.get(aid))

    // Generic processing simulator for each unique equipment
    equipIds.foreach { eid =>
      val areaType = routing.steps.find(s => FabFlowEngine.AreaToEquipmentId.get(s.equipmentArea.areaId).contains(eid))
        .map(_.equipmentArea.areaId).getOrElse(eid)
      val equipCfg = EquipmentConfig(eid, areaType, processingTime = 8.seconds)
      val actor = system.systemActorOf(
        new GenericEquipmentSimulator().apply(equipCfg),
        s"dyn-equip-$eid-${System.currentTimeMillis()}"
      )
      adapter.registerSimulator(eid, actor)
      publisher(net.imadz.fab.events.EquipmentStateChanged(eid, areaType, "Idle", None))
    }

    // CD-SEM simulator (for measurement)
    val cdSemId = FabFlowEngine.CdsemEquipId
    val cdSemCfg = CdSemConfig(
      waferIds = waferIds,
      targetCdNm = 32.0,
      waferOutcomes = Map.empty // will use random generation
    )
    val cdSemActor = system.systemActorOf(
      new CdSemSimulator(cdSemCfg)(EquipmentConfig(cdSemId, "METROLOGY", processingTime = 5.seconds)),
      s"dyn-cdsem-${System.currentTimeMillis()}"
    )
    adapter.registerSimulator(cdSemId, cdSemActor)
    publisher(net.imadz.fab.events.EquipmentStateChanged(cdSemId, "METROLOGY", "Idle", None))

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
    publisher(net.imadz.fab.events.EquipmentStateChanged(stockerId, "STOCKER", "Idle", None))
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
      val equipId = net.imadz.fab.chain.FabFlowEngine.AreaToEquipmentId.getOrElse(step.equipmentArea.areaId, s"${step.equipmentArea.areaId}-01")
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
    import net.imadz.fab.projection.FabDemoViewProjection
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
    import net.imadz.fab.routing._

    // 1. Basic Rework route
    RoutingRepository.publish(RouteDefinition(
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
      ocapRules = List(
        OcapRuleDefinition("OCAP-001","Borderline→Rework",
          MeasurementCondition("cd_nm",WithinRange,34.0,36.0,AnyWafer),
          OcapComposite(List(OcapRework("REWORK-LITHO-001",2),OcapNotify("CD borderline","area-engineer"))),priority=1),
        OcapRuleDefinition("OCAP-002","FarOut→Scrap",
          MeasurementCondition("cd_nm",GreaterThan,42.0,0.0,AnyWafer),
          OcapScrap("CD far out of spec"),priority=0)
      )
    ))

    // 2. Send-Ahead Pilot
    RoutingRepository.publish(RouteDefinition(
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
    RoutingRepository.publish(RouteDefinition(
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
    RoutingRepository.publish(RouteDefinition(
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
    RoutingRepository.publish(RouteDefinition(
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

}
