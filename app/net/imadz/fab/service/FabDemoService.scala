package net.imadz.fab.service

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.AskPattern._
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.util.Timeout
import net.imadz.application.aggregates.LotAggregate.LotEntityTypeKey
import net.imadz.application.aggregates.WaferAggregate.WaferEntityTypeKey
import net.imadz.application.aggregates.LotProtocol._
import net.imadz.application.aggregates.WaferProtocol._
import net.imadz.application.aggregates.process.FabProcessAggregate
import net.imadz.application.aggregates.process.FabProcessProtocol.ProcessConfirmation
import net.imadz.application.services.FabSagaService
import net.imadz.application.services.transactor.FabSagaProtocol.FabSagaConfirmation
import net.imadz.common.CommonTypes.Id
import net.imadz.fab.chain.{FabChainExecutor, FabDemoPipeline, FabFlowEngine, FabScenarioPipeline}
import net.imadz.fab.chain.FabChainExecutor.{ChainResult, StartChain}
import net.imadz.fab.chain.FabDemoPipeline.{FabDemoContext, FabDemoState, WaferInfo}
import net.imadz.fab.events.FabSimulationEvent
import net.imadz.fab.model.{EquipmentArea, ProductRouting, ProductRoutingRepository}
import net.imadz.fab.protocol.ActorEquipmentAdapter
import net.imadz.fab.scenario.{DecisionConfig, FabSimulationScenario, StandardScenarios}
import net.imadz.fab.simulation._

import java.util.UUID
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

  /**
   * Start a demo scenario using M2.5+ Chain-aligned FabChainExecutor.
   * Creates EventSourced Lot + Wafer aggregates, wires FabSagaService for TCC split/merge,
   * then runs the 11-stage pipeline via FabChainExecutor (EventSourcedBehavior).
   */
  def startDemo(scenarioId: String, publisher: FabSimulationEvent => Unit): Future[ChainResult] = {
    val scenario = scenarioId match {
      case "photo-cell-5wafer" => StandardScenarios.photoCell5Wafer
      case "send-ahead-pilot"  => StandardScenarios.sendAheadPilot
      case "scrap-downgrade"   => StandardScenarios.scrapDowngrade
      case "sampling-demo"     => StandardScenarios.samplingDemo
      case "hold-release"      => StandardScenarios.holdRelease
      case _                   => StandardScenarios.photoCell5Wafer
    }
    val isRework = scenarioId == "photo-cell-5wafer"

    // Generate unique UUIDs for this run
    val runKey = UUID.randomUUID().toString.take(8)
    val waferUUIDs: Map[String, Id] = scenario.waferIds.map { wid =>
      wid -> UUID.nameUUIDFromBytes(s"$runKey-$wid".getBytes)
    }.toMap
    val sourceLotId: Id = UUID.nameUUIDFromBytes(s"$runKey-source-lot".getBytes)
    val reworkLotId: Id = UUID.nameUUIDFromBytes(s"$runKey-rework-lot".getBytes)

    // Child lots for new scenarios
    val pilotLotId: Id = UUID.nameUUIDFromBytes(s"$runKey-pilot-lot".getBytes)
    val sampleLotId: Id = UUID.nameUUIDFromBytes(s"$runKey-sample-lot".getBytes)
    val holdLotId: Id = UUID.nameUUIDFromBytes(s"$runKey-hold-lot".getBytes)

    val lotRef = sharding.entityRefFor(LotEntityTypeKey, sourceLotId.toString)
    val reworkLotRef = sharding.entityRefFor(LotEntityTypeKey, reworkLotId.toString)
    val pilotLotRef = sharding.entityRefFor(LotEntityTypeKey, pilotLotId.toString)
    val sampleLotRef = sharding.entityRefFor(LotEntityTypeKey, sampleLotId.toString)
    val holdLotRef = sharding.entityRefFor(LotEntityTypeKey, holdLotId.toString)
    val waferRefs: Map[String, akka.cluster.sharding.typed.scaladsl.EntityRef[WaferCommand]] =
      waferUUIDs.map { case (wid, uuid) => wid -> sharding.entityRefFor(WaferEntityTypeKey, uuid.toString) }

    val sagaTxFn: (Id, Id, Set[Id]) => Future[FabSagaConfirmation] =
      (srcId, tgtId, wids) => fabSagaService.transferWafers(srcId, tgtId, wids)

    // Create source Lot + scenario-specific child Lots + Wafers
    val childLotFutures: Seq[Future[LotConfirmation]] = scenarioId match {
      case "photo-cell-5wafer" =>
        Seq(reworkLotRef.ask[LotConfirmation](ref => CreateLot(s"FAB-REWORK-$runKey", Set.empty, ref)))
      case "send-ahead-pilot" =>
        Seq(pilotLotRef.ask[LotConfirmation](ref => CreateLot(s"FAB-PILOT-$runKey", Set.empty, ref)))
      case "sampling-demo" =>
        Seq(sampleLotRef.ask[LotConfirmation](ref => CreateLot(s"FAB-SAMPLE-$runKey", Set.empty, ref)))
      case "hold-release" =>
        Seq(holdLotRef.ask[LotConfirmation](ref => CreateLot(s"FAB-HOLD-$runKey", Set.empty, ref)))
      case _ => Seq.empty // scrap-downgrade / dynamic products: no child lots
    }
    val createEntities: Future[Unit] = for {
      _ <- lotRef.ask[LotConfirmation](ref =>
        CreateLot(s"FAB-$runKey", waferUUIDs.values.toSet, ref)
      )
      _ <- Future.sequence(childLotFutures)
      _ <- Future.sequence(waferUUIDs.map { case (_, uuid) =>
        val waferRef = sharding.entityRefFor(WaferEntityTypeKey, uuid.toString)
        waferRef.ask[WaferConfirmation](ref => CreateWafer(sourceLotId, ref))
      })
    } yield ()

    // After entities are created, start the chain executor
    createEntities.flatMap { _ =>
      val processId = UUID.randomUUID().toString
      val processRef = sharding.entityRefFor(FabProcessAggregate.ProcessEntityTypeKey, processId)
      val adapter = new ActorEquipmentAdapter()

      // Fire-and-forget reply targets
      val ignoreReply = system.systemActorOf(
        akka.actor.typed.scaladsl.Behaviors.ignore[ProcessConfirmation],
        s"proc-ignore-$runKey"
      )
      val ignoreLotReply = system.systemActorOf(
        akka.actor.typed.scaladsl.Behaviors.ignore[LotConfirmation],
        s"lot-ignore-$runKey"
      )
      val ignoreWaferReply = system.systemActorOf(
        akka.actor.typed.scaladsl.Behaviors.ignore[WaferConfirmation],
        s"wafer-ignore-$runKey"
      )

      val ctx = FabDemoContext(
        scenario = scenario,
        foupId = s"FOUP-${scenario.scenarioId}",
        processRef = processRef,
        lotRef = lotRef,
        reworkLotRef = reworkLotRef,
        waferRefs = waferRefs,
        waferUUIDs = waferUUIDs,
        sourceLotId = sourceLotId,
        reworkLotId = reworkLotId,
        adapter = adapter,
        publisher = publisher,
        ignoreReply = ignoreReply,
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

      // Publish DemoStarted before launching chain
      publisher(net.imadz.fab.events.DemoStarted(scenario.scenarioId, scenario.name, scenario.lotSize, scenario.waferIds))

      // Spawn simulators (same as before)
      spawnSimulators(scenario, adapter, publisher)

      val pipelineFn = if (isRework) FabDemoPipeline.runPipeline _ else FabScenarioPipeline.runPipeline _
      val executor = system.systemActorOf(
        FabChainExecutor(runKey, initialState, ctx, pipelineFn),
        s"fab-chain-${scenarioId}-${System.currentTimeMillis()}"
      )

      executor.ask[ChainResult](ref => StartChain(ref))
    }
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
  def startDemoWithProduct(productId: String, publisher: FabSimulationEvent => Unit): Future[ChainResult] = {
    val routing = ProductRoutingRepository.findByProductId(productId)
      .getOrElse(throw new IllegalArgumentException(s"Unknown product: $productId"))

    // Synthetic scenario for FabDemoContext compatibility
    val waferIds = (1 to 5).map(i => s"WAFER-$i")
    val syntheticScenario = FabSimulationScenario(
      scenarioId = productId,
      name = s"Dynamic: ${routing.productId}",
      description = s"POR-based dynamic routing (${routing.steps.size} steps, v${routing.version})",
      lotSize = 5,
      waferIds = waferIds,
      litho = EquipmentConfig("LITHO-01", "LITHO", processingTime = 8.seconds),
      lithoDetail = LithoConfig(waferCount = 5),
      cdSem = EquipmentConfig("CDSEM-01", "METROLOGY", processingTime = 5.seconds),
      cdSemDetail = CdSemConfig(waferIds = waferIds, targetCdNm = 32.0, waferOutcomes = waferIds.map(_ -> "PASS").toMap),
      amhs = AmhsConfig(routes = FabFlowEngine.DefaultRoutes.map { case (k, v) => k -> v }, maxConcurrentTransports = 5),
      stocker = StockerConfig("STOCKER-01", portCount = 4, loadTime = 2.seconds),
      decision = FabFlowEngine.DefaultDecisionConfig
    )

    val runKey = UUID.randomUUID().toString.take(8)
    val waferUUIDs: Map[String, Id] = waferIds.map { wid =>
      wid -> UUID.nameUUIDFromBytes(s"$runKey-$wid".getBytes)
    }.toMap
    val sourceLotId: Id = UUID.nameUUIDFromBytes(s"$runKey-source-lot".getBytes)
    val reworkLotId: Id = UUID.nameUUIDFromBytes(s"$runKey-rework-lot".getBytes)

    val lotRef = sharding.entityRefFor(LotEntityTypeKey, sourceLotId.toString)
    val reworkLotRef = sharding.entityRefFor(LotEntityTypeKey, reworkLotId.toString)
    val waferRefs: Map[String, akka.cluster.sharding.typed.scaladsl.EntityRef[WaferCommand]] =
      waferUUIDs.map { case (wid, uuid) => wid -> sharding.entityRefFor(WaferEntityTypeKey, uuid.toString) }

    val sagaTxFn: (Id, Id, Set[Id]) => Future[FabSagaConfirmation] =
      (srcId, tgtId, wids) => fabSagaService.transferWafers(srcId, tgtId, wids)

    val createEntities: Future[Unit] = for {
      _ <- lotRef.ask[LotConfirmation](ref => CreateLot(s"FAB-$runKey", waferUUIDs.values.toSet, ref))
      _ <- reworkLotRef.ask[LotConfirmation](ref => CreateLot(s"FAB-REWORK-$runKey", Set.empty, ref))
      _ <- Future.sequence(waferUUIDs.map { case (_, uuid) =>
        val waferRef = sharding.entityRefFor(WaferEntityTypeKey, uuid.toString)
        waferRef.ask[WaferConfirmation](ref => CreateWafer(sourceLotId, ref))
      })
    } yield ()

    createEntities.flatMap { _ =>
      val processId = UUID.randomUUID().toString
      val processRef = sharding.entityRefFor(FabProcessAggregate.ProcessEntityTypeKey, processId)
      val adapter = new ActorEquipmentAdapter()

      val ignoreReply = system.systemActorOf(
        akka.actor.typed.scaladsl.Behaviors.ignore[ProcessConfirmation], s"proc-ignore-$runKey")
      val ignoreLotReply = system.systemActorOf(
        akka.actor.typed.scaladsl.Behaviors.ignore[LotConfirmation], s"lot-ignore-$runKey")
      val ignoreWaferReply = system.systemActorOf(
        akka.actor.typed.scaladsl.Behaviors.ignore[WaferConfirmation], s"wafer-ignore-$runKey")

      val ctx = FabDemoContext(
        scenario = syntheticScenario,
        foupId = s"FOUP-${routing.productId}",
        processRef = processRef,
        lotRef = lotRef,
        reworkLotRef = reworkLotRef,
        waferRefs = waferRefs,
        waferUUIDs = waferUUIDs,
        sourceLotId = sourceLotId,
        reworkLotId = reworkLotId,
        adapter = adapter,
        publisher = publisher,
        ignoreReply = ignoreReply,
        ignoreLotReply = ignoreLotReply,
        ignoreWaferReply = ignoreWaferReply,
        sagaTx = sagaTxFn,
        speedMultiplier = 1.0
      )

      val initialState = FabDemoState(
        wafers = waferIds.map(wid => wid -> WaferInfo(wid)).toMap
      )

      publisher(net.imadz.fab.events.DemoStarted(productId, routing.productId, 5, waferIds))

      // Spawn simulators for all areas used in routing + AMHS + CDSEM + STOCKER
      spawnDynamicSimulators(routing, adapter, publisher, waferIds)

      val pipelineFn = FabFlowEngine.runRouting(routing, FabFlowEngine.DefaultDecisionConfig) _
      val executor = system.systemActorOf(
        FabChainExecutor(runKey, initialState, ctx, pipelineFn),
        s"fab-chain-${routing.productId}-${System.currentTimeMillis()}"
      )

      executor.ask[ChainResult](ref => StartChain(ref))
    }
  }

  /** Spawn generic equipment simulators for all areas used in a routing. */
  private def spawnDynamicSimulators(
    routing: ProductRouting,
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
    val dynamicProducts = ProductRoutingRepository.listProducts.map { r =>
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
      case productId if ProductRoutingRepository.findByProductId(productId).isDefined =>
        val routing = ProductRoutingRepository.findByProductId(productId).get
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

  private def generateRoutingLedger(routing: ProductRouting): Seq[Map[String, String]] = {
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
}
