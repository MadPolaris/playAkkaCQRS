package net.imadz.fab.service

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.util.Timeout
import net.imadz.application.aggregates.LotAggregate.LotEntityTypeKey
import net.imadz.application.aggregates.WaferAggregate.WaferEntityTypeKey
import net.imadz.application.aggregates.LotProtocol._
import net.imadz.application.aggregates.WaferProtocol._
import net.imadz.application.aggregates.WorkOrderAggregate
import net.imadz.application.aggregates.WorkOrderProtocol.{CreateWorkOrder, WorkOrderConfirmation, PipelineStarter => WorkOrderPipelineStarter}
import net.imadz.application.services.FabSagaService
import net.imadz.application.services.transactor.FabSagaProtocol.FabSagaConfirmation
import net.imadz.common.CommonTypes.Id
import net.imadz.fab.chain.{FabDemoPipeline, FabFlowEngine, FabScenarioPipeline}
import net.imadz.fab.model.FabExecutionModel.{FabDemoContext, FabDemoState, WaferInfo}
import net.imadz.fab.events.{DemoStarted, FabSimulationEvent}
import net.imadz.fab.model.{Por, PorRepository}
import net.imadz.fab.protocol.ActorEquipmentAdapter
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

  // --- Register WorkOrder Aggregate with ClusterSharding (idempotent) ---
  WorkOrderAggregate.init(sharding, createPipelineStarter())

  /**
   * Shared PipelineStarter — creates entities, builds context, spawns simulators,
   * and runs the pipeline. Called both on initial StartChain and on RecoveryCompleted
   * replay (idempotent via deterministic UUIDs from workOrderId).
   */
  private def createPipelineStarter(): WorkOrderPipelineStarter = {
    (workOrderId: String, productId: String, waferIds: Seq[String], _: Any => Unit) =>
      // Access systemWidePublisher LAZILY at runtime (not captured at factory-creation time)
      // so it's available even for crash-recovery replays long after controller init.
      val publisher: FabSimulationEvent => Unit = systemWidePublisher.getOrElse {
        val fromRegistry = publisherRegistry.remove(workOrderId)
        if (fromRegistry != null) fromRegistry.asInstanceOf[FabSimulationEvent => Unit]
        else (_: FabSimulationEvent) => ()
      }
      val stateFut = PorRepository.findByProductId(productId) match {
        case Some(routing) =>
          runDynamicPor(workOrderId, productId, routing, waferIds, publisher)
        case None =>
          runStaticScenario(workOrderId, productId, waferIds, publisher)
      }
      stateFut.map(s => (s.passCount, s.scrapCount, s.wafers.values.count(_.reworkCount > 0)))(ec)
  }

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

    val lotRef = sharding.entityRefFor(LotEntityTypeKey, sourceLotId.toString)
    val reworkLotRef = sharding.entityRefFor(LotEntityTypeKey, reworkLotId.toString)
    val waferRefs: Map[String, akka.cluster.sharding.typed.scaladsl.EntityRef[WaferCommand]] =
      waferUUIDs.map { case (wid, uuid) => wid -> sharding.entityRefFor(WaferEntityTypeKey, uuid.toString) }

    val sagaTxFn: (Id, Id, Set[Id]) => Future[FabSagaConfirmation] =
      (srcId, tgtId, wids) => fabSagaService.transferWafers(srcId, tgtId, wids)

    val adapter = new ActorEquipmentAdapter()

    val ignoreLotReply = system.ignoreRef[LotConfirmation]
    val ignoreWaferReply = system.ignoreRef[WaferConfirmation]

    val ctx = FabDemoContext(
      scenario = syntheticScenario,
      foupId = s"FOUP-${routing.productId}",
      lotRef = lotRef,
      reworkLotRef = reworkLotRef,
      waferRefs = waferRefs,
      waferUUIDs = waferUUIDs,
      sourceLotId = sourceLotId,
      reworkLotId = reworkLotId,
      adapter = adapter,
      publisher = publisher,
      ignoreLotReply = ignoreLotReply,
      ignoreWaferReply = ignoreWaferReply,
      sagaTx = sagaTxFn,
      speedMultiplier = 1.0
    )

    val initialState = FabDemoState(
      wafers = waferIds.map(wid => wid -> WaferInfo(wid)).toMap
    )

    publisher(DemoStarted(productId, routing.productId, waferIds.size, waferIds))

    val pipelineFn = FabFlowEngine.runRouting(routing, FabFlowEngine.DefaultDecisionConfig) _

    // Create entities (idempotent) then run pipeline
    for {
      _ <- lotRef.ask[LotConfirmation](ref => CreateLot(s"FAB-$workOrderId", waferUUIDs.values.toSet, ref))
      _ <- reworkLotRef.ask[LotConfirmation](ref => CreateLot(s"FAB-REWORK-$workOrderId", Set.empty, ref))
      _ <- Future.sequence(waferUUIDs.map { case (_, uuid) =>
        val waferRef = sharding.entityRefFor(WaferEntityTypeKey, uuid.toString)
        waferRef.ask[WaferConfirmation](ref => CreateWafer(sourceLotId, ref))
      })
      _ = spawnDynamicSimulators(routing, adapter, publisher, waferIds)
      result <- pipelineFn(initialState, ctx)
    } yield result
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
    val pilotLotId: Id = UUID.nameUUIDFromBytes(s"$workOrderId-pilot-lot".getBytes)
    val sampleLotId: Id = UUID.nameUUIDFromBytes(s"$workOrderId-sample-lot".getBytes)
    val holdLotId: Id = UUID.nameUUIDFromBytes(s"$workOrderId-hold-lot".getBytes)

    val lotRef = sharding.entityRefFor(LotEntityTypeKey, sourceLotId.toString)
    val reworkLotRef = sharding.entityRefFor(LotEntityTypeKey, reworkLotId.toString)
    val pilotLotRef = sharding.entityRefFor(LotEntityTypeKey, pilotLotId.toString)
    val sampleLotRef = sharding.entityRefFor(LotEntityTypeKey, sampleLotId.toString)
    val holdLotRef = sharding.entityRefFor(LotEntityTypeKey, holdLotId.toString)
    val waferRefs: Map[String, akka.cluster.sharding.typed.scaladsl.EntityRef[WaferCommand]] =
      waferUUIDs.map { case (wid, uuid) => wid -> sharding.entityRefFor(WaferEntityTypeKey, uuid.toString) }

    val sagaTxFn: (Id, Id, Set[Id]) => Future[FabSagaConfirmation] =
      (srcId, tgtId, wids) => fabSagaService.transferWafers(srcId, tgtId, wids)

    val adapter = new ActorEquipmentAdapter()

    val ignoreLotReply = system.ignoreRef[LotConfirmation]
    val ignoreWaferReply = system.ignoreRef[WaferConfirmation]

    val ctx = FabDemoContext(
      scenario = scenario,
      foupId = s"FOUP-${scenario.scenarioId}",
      lotRef = lotRef,
      reworkLotRef = reworkLotRef,
      waferRefs = waferRefs,
      waferUUIDs = waferUUIDs,
      sourceLotId = sourceLotId,
      reworkLotId = reworkLotId,
      adapter = adapter,
      publisher = publisher,
      ignoreLotReply = ignoreLotReply,
      ignoreWaferReply = ignoreWaferReply,
      sagaTx = sagaTxFn,
      speedMultiplier = 1.0,
      childLotRefs = Map(
        "pilot" -> pilotLotRef,
        "sample" -> sampleLotRef,
        "hold" -> holdLotRef
      ),
      childLotIds = Map(
        "pilot" -> pilotLotId,
        "sample" -> sampleLotId,
        "hold" -> holdLotId
      )
    )

    val initialState = FabDemoState(
      wafers = scenario.waferIds.map(wid => wid -> WaferInfo(wid)).toMap
    )

    publisher(DemoStarted(scenario.scenarioId, scenario.name, scenario.lotSize, scenario.waferIds))
    spawnSimulators(scenario, adapter, publisher)

    // Create entities (idempotent)
    val childLotFutures: Seq[Future[LotConfirmation]] = scenarioId match {
      case "photo-cell-5wafer" =>
        Seq(reworkLotRef.ask[LotConfirmation](ref => CreateLot(s"FAB-REWORK-$workOrderId", Set.empty, ref)))
      case "send-ahead-pilot" =>
        Seq(pilotLotRef.ask[LotConfirmation](ref => CreateLot(s"FAB-PILOT-$workOrderId", Set.empty, ref)))
      case "sampling-demo" =>
        Seq(sampleLotRef.ask[LotConfirmation](ref => CreateLot(s"FAB-SAMPLE-$workOrderId", Set.empty, ref)))
      case "hold-release" =>
        Seq(holdLotRef.ask[LotConfirmation](ref => CreateLot(s"FAB-HOLD-$workOrderId", Set.empty, ref)))
      case _ => Seq.empty
    }

    val pipelineFn = if (isRework) FabDemoPipeline.runPipeline _ else FabScenarioPipeline.runPipeline _

    for {
      _ <- lotRef.ask[LotConfirmation](ref => CreateLot(s"FAB-$workOrderId", waferUUIDs.values.toSet, ref))
      _ <- Future.sequence(childLotFutures)
      _ <- Future.sequence(waferUUIDs.map { case (_, uuid) =>
        val waferRef = sharding.entityRefFor(WaferEntityTypeKey, uuid.toString)
        waferRef.ask[WaferConfirmation](ref => CreateWafer(sourceLotId, ref))
      })
      result <- pipelineFn(initialState, ctx)
    } yield result
  }

  /**
   * Start a demo scenario using M2.5+ Chain-aligned FabChainExecutor.
   * Creates EventSourced Lot + Wafer aggregates, wires FabSagaService for TCC split/merge,
   * then runs the 11-stage pipeline via FabChainExecutor (EventSourcedBehavior).
   */
  def startDemo(scenarioId: String, publisher: FabSimulationEvent => Unit): Future[WorkOrderConfirmation] = {
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
    ref.ask[WorkOrderConfirmation](replyTo => CreateWorkOrder(scenarioId, scenario.waferIds, replyTo))
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
    PorRepository.findByProductId(productId)
      .getOrElse(throw new IllegalArgumentException(s"Unknown product: $productId"))
    val waferIds = (1 to 5).map(i => s"WAFER-$i")
    val workOrderId = UUID.randomUUID().toString

    publisherRegistry.put(workOrderId, publisher.asInstanceOf[Any => Unit])
    val ref = sharding.entityRefFor(WorkOrderAggregate.WorkOrderEntityTypeKey, workOrderId)
    ref.ask[WorkOrderConfirmation](replyTo => CreateWorkOrder(productId, waferIds, replyTo))
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
   * Uses deterministic UUIDs to reconstruct entity refs and sends
   * GetLotState / GetWaferState commands via ClusterSharding ask pattern.
   */
  def queryEntityState(workOrderId: String): Future[EntityStateSnapshot] = {
    import net.imadz.application.aggregates.LotProtocol.GetLotState
    import net.imadz.application.aggregates.WaferProtocol.GetWaferState
    import java.util.UUID

    val sourceLotUUID = UUID.nameUUIDFromBytes(s"$workOrderId-source-lot".getBytes)
    val lotRef = sharding.entityRefFor(LotEntityTypeKey, sourceLotUUID.toString)

    lotRef.ask[LotConfirmation](GetLotState(_)).flatMap { lotConf =>
      // Map wafer UUIDs back from workOrderId + waferId (deterministic)
      val waferIds: Set[String] = lotConf.waferIds.map(_.toString)
      val reworkLotUUID = UUID.nameUUIDFromBytes(s"$workOrderId-rework-lot".getBytes)
      val reworkLotRef = sharding.entityRefFor(LotEntityTypeKey, reworkLotUUID.toString)

      // Query rework lot if it exists
      val reworkLotFuture: Future[Option[LotConfirmation]] = reworkLotRef
        .ask[LotConfirmation](GetLotState(_))
        .map { conf =>
          if (conf.waferIds.nonEmpty) Some(conf) else None
        }
        .recover { case _ => None }

      // Query all wafers in the source lot
      val waferFutures: Seq[Future[(String, WaferConfirmation)]] = waferIds.toSeq.map { wid =>
        val waferUUID = UUID.nameUUIDFromBytes(s"$workOrderId-$wid".getBytes)
        val waferRef = sharding.entityRefFor(WaferEntityTypeKey, waferUUID.toString)
        waferRef.ask[WaferConfirmation](GetWaferState(_))
          .map(wc => wid -> wc)
          .recover { case _ => wid -> WaferConfirmation(error = None) }
      }

      Future.sequence(waferFutures).flatMap { wafers =>
        reworkLotFuture.map { reworkLot =>
          EntityStateSnapshot(
            workOrderId = workOrderId,
            lot = lotConf,
            reworkLot = reworkLot,
            wafers = wafers.toMap
          )
        }
      }
    }
  }

  case class EntityStateSnapshot(
    workOrderId: String,
    lot: LotConfirmation,
    reworkLot: Option[LotConfirmation],
    wafers: Map[String, WaferConfirmation]
  )

}
