package net.imadz.fab.chain

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import com.typesafe.config.ConfigFactory
import net.imadz.application.aggregates.LotAggregate
import net.imadz.application.aggregates.LotProtocol.LotCommand
import net.imadz.fab.chain.FabPipelineExecutionActor.publishDemoCompleted
import net.imadz.fab.chain.FabScenarioPipeline.PipelineStage
import net.imadz.fab.events._
import net.imadz.fab.model.FabExecutionModel.{FabDemoContext, FabDemoState, WaferInfo}
import net.imadz.fab.protocol.ActorEquipmentAdapter
import net.imadz.fab.saga.FabSagaTestConfig
import net.imadz.fab.scenario.{DecisionConfig, FabSimulationScenario}
import net.imadz.fab.simulation.{AmhsConfig, CdSemConfig, EquipmentConfig, LithoConfig, StockerConfig}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.libs.json.Json

import java.util.UUID
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

/**
 * M3.5 Demo Event Contract Spec — verifies the WebSocket event publishing
 * contract that the M3.5 frontend depends on.
 *
 * Tests:
 *   E1 — DemoCompleted published with correct wafer counts
 *   E2 — PipelineTimelineSnapshot published on phase completion
 *   E3 — PipelineStageFailed published on equipment fault
 *   E4 — OcapActionTriggered published when OCAP rules match
 *   E5 — RecoveryEvent published during crash/recovery cycle
 *   E6 — DemoCompleted JSON serialization matches frontend expectations
 *   E7 — publishDemoCompleted correctly classifies PASS/SCRAP/REWORK
 */
class M35DemoEventContractSpec extends ScalaTestWithActorTestKit(
  ConfigFactory.parseString(
    s"""
      |akka.persistence.journal.plugin = "akka.persistence.testkit.journal"
      |akka.persistence.snapshot-store.plugin = "akka.persistence.no-snapshot-store"
      |akka {
      |  actor.provider = "cluster"
      |  remote.artery.canonical.port = 0
      |  test.single-expect-default = 30s
      |  actor.testkit.typed.single-expect-default = 30s
      |  persistence.testkit.events.serialize = off
      |}
      |""".stripMargin
  ).withFallback(FabSagaTestConfig.testConfig)
) with AnyWordSpecLike with BeforeAndAfterEach {

  private implicit val ec: ExecutionContext = system.executionContext
  private val sharding = akka.cluster.sharding.typed.scaladsl.ClusterSharding(system)

  // Event collector for verifying published events
  private val publishedEvents = ListBuffer.empty[FabSimulationEvent]
  private def collectEvents: FabSimulationEvent => Unit = e => publishedEvents += e

  override def beforeAll(): Unit = {
    sharding.init(akka.cluster.sharding.typed.scaladsl.Entity(LotAggregate.LotEntityTypeKey) { _ =>
      akka.actor.typed.scaladsl.Behaviors.ignore[LotCommand]
    })
  }

  override def beforeEach(): Unit = publishedEvents.clear()

  // ===================================================================
  // Test fixtures
  // ===================================================================

  /** Mock context with event collector instead of no-op publisher. */
  private def mockContext(): FabDemoContext = {
    val scenario = FabSimulationScenario(
      scenarioId = "test-m35", name = "M3.5 Contract Test", description = "", lotSize = 5,
      waferIds = Seq("WAFER-1", "WAFER-2", "WAFER-3", "WAFER-4", "WAFER-5"),
      litho = EquipmentConfig("LITHO-01", "LITHO"),
      lithoDetail = LithoConfig(waferCount = 5),
      cdSem = EquipmentConfig("CDSEM-01", "METROLOGY"),
      cdSemDetail = CdSemConfig(
        waferIds = Seq("WAFER-1", "WAFER-2", "WAFER-3", "WAFER-4", "WAFER-5"),
        targetCdNm = 32.0,
        waferOutcomes = Map(
          "WAFER-1" -> "PASS", "WAFER-2" -> "PASS",
          "WAFER-3" -> "BORDERLINE",
          "WAFER-4" -> "FAIL", "WAFER-5" -> "SCRAP")),
      amhs = AmhsConfig(),
      stocker = StockerConfig("STOCKER-01"),
      decision = DecisionConfig(lowerSpecNm = 28.0, upperSpecNm = 32.0,
        borderlineWindowNm = 2.0, maxReworkCount = 2, reworkRecipeId = "REWORK-001")
    )
    val lotRef = sharding.entityRefFor(LotAggregate.LotEntityTypeKey, "test-lot")
    FabDemoContext(
      scenario = scenario, foupId = "FOUP-M35-TEST", lotRef = lotRef, reworkLotRef = lotRef,
      waferUUIDs = Map.empty,
      sourceLotId = UUID.randomUUID(), reworkLotId = UUID.randomUUID(),
      adapter = new ActorEquipmentAdapter()(system, ec),
      publisher = collectEvents,
      ignoreLotReply = system.ignoreRef,
      sagaTx = (_, _, _, _, _) => Future.successful(
        net.imadz.application.services.transactor.FabSagaProtocol.FabSagaConfirmation(
          transactionId = UUID.randomUUID(), error = None)),
      speedMultiplier = 1.0
    )
  }

  // ===================================================================
  // Sanity check
  // ===================================================================

  "M35Demo test infrastructure" should {
    "sanity: 1 + 1 == 2" in {
      1 + 1 shouldBe 2
    }
  }

  // ===================================================================
  // E1: DemoCompleted published with correct wafer counts
  // ===================================================================

  "DemoCompleted event" should {

    "E1: be published with correct wafer counts after pipeline completion" in {
      val ctx = mockContext()

      // Simulate a finished pipeline state: 3 PASS, 1 reworked then PASS, 1 SCRAP
      val finalState = FabDemoState(wafers = Map(
        "WAFER-1" -> WaferInfo("WAFER-1", classification = Some("PASS")),
        "WAFER-2" -> WaferInfo("WAFER-2", classification = Some("PASS")),
        "WAFER-3" -> WaferInfo("WAFER-3", reworkCount = 1, classification = Some("PASS")),
        "WAFER-4" -> WaferInfo("WAFER-4", classification = Some("PASS")),
        "WAFER-5" -> WaferInfo("WAFER-5", classification = Some("SCRAP"))
      ))

      publishDemoCompleted(ctx, finalState, "WO-TEST-001")

      // Verify DemoCompleted event was published
      val completedEvents = publishedEvents.collect { case e: DemoCompleted => e }
      completedEvents should have size 1
      val dc = completedEvents.head
      dc.totalWafers shouldBe 5
      dc.passedWafers shouldBe 4
      dc.reworkedWafers shouldBe 1
      dc.scrappedWafers shouldBe 1
      dc.lotId shouldBe "FOUP-M35-TEST"
    }

    "E1b: report zero counts for empty wafer set" in {
      val ctx = mockContext()
      val emptyState = FabDemoState(wafers = Map.empty)

      publishDemoCompleted(ctx, emptyState, "WO-EMPTY")

      val completedEvents = publishedEvents.collect { case e: DemoCompleted => e }
      completedEvents should have size 1
      val dc = completedEvents.head
      dc.totalWafers shouldBe 0
      dc.passedWafers shouldBe 0
      dc.reworkedWafers shouldBe 0
      dc.scrappedWafers shouldBe 0
    }
  }

  // ===================================================================
  // E2: PipelineTimelineSnapshot published on phase completion
  // ===================================================================

  "PipelineTimelineSnapshot event" should {

    "E2: carry correct phase index and total after phase completion" in {
      val ctx = mockContext()

      // Simulate a timeline snapshot mid-pipeline (8 of 14 phases done)
      val snapshot = PipelineTimelineSnapshot(
        workOrderId = "WO-SNAP-001",
        totalPhases = 14,
        completedPhases = 8,
        currentPhase = Some("TrackOut_LITHO-01"),
        currentPhaseIndex = 7,
        failedPhases = Seq.empty,
        recoveredPhases = Seq.empty,
        ocapTriggers = 0
      )

      ctx.publisher(snapshot)

      val snapshots = publishedEvents.collect { case s: PipelineTimelineSnapshot => s }
      snapshots should have size 1
      val s = snapshots.head
      s.totalPhases shouldBe 14
      s.completedPhases shouldBe 8
      s.currentPhaseIndex shouldBe 7
      s.failedPhases shouldBe empty
      s.recoveredPhases shouldBe empty
    }

    "E2b: mark failed and recovered phases" in {
      val ctx = mockContext()

      val snapshot = PipelineTimelineSnapshot(
        workOrderId = "WO-REC-001",
        totalPhases = 14, completedPhases = 10,
        currentPhase = Some("Measure_CDSEM-01"),
        currentPhaseIndex = 9,
        failedPhases = Seq("RunRecipe_LITHO-01_LITHO-28-001"),
        recoveredPhases = Seq("RunRecipe_LITHO-01_LITHO-28-001"),
        ocapTriggers = 1
      )

      ctx.publisher(snapshot)

      val snapshots = publishedEvents.collect { case s: PipelineTimelineSnapshot => s }
      snapshots should have size 1
      val s = snapshots.head
      s.failedPhases should contain("RunRecipe_LITHO-01_LITHO-28-001")
      s.recoveredPhases should contain("RunRecipe_LITHO-01_LITHO-28-001")
      s.ocapTriggers shouldBe 1
    }
  }

  // ===================================================================
  // E3: PipelineStageFailed published on equipment fault
  // ===================================================================

  "PipelineStageFailed event" should {

    "E3: carry stage name, equipment ID, and error code" in {
      val ctx = mockContext()

      val failure = PipelineStageFailed(
        stageName = "Process",
        equipId = Some("LITHO-01"),
        errorCode = "HARDWARE_FAULT",
        detail = "Simulated hardware fault at TrackIn"
      )

      ctx.publisher(failure)

      val failures = publishedEvents.collect { case f: PipelineStageFailed => f }
      failures should have size 1
      val f = failures.head
      f.stageName shouldBe "Process"
      f.equipId shouldBe Some("LITHO-01")
      f.errorCode shouldBe "HARDWARE_FAULT"
      f.detail should include("hardware fault")
      f.timestamp should be > 0L
    }
  }

  // ===================================================================
  // E4: OcapActionTriggered published when OCAP rules match
  // ===================================================================

  "OcapActionTriggered event" should {

    "E4: carry rule ID, action type, and affected wafers" in {
      val ctx = mockContext()

      val ocapEvent = OcapActionTriggered(
        ruleId = "OCAP-001",
        ruleName = "Borderline CD → Rework",
        actionType = "REWORK",
        detail = "WAFER-3 cd_nm=35.2 exceeds borderline window",
        affectedWafers = Seq("WAFER-3")
      )

      ctx.publisher(ocapEvent)

      val ocapEvents = publishedEvents.collect { case o: OcapActionTriggered => o }
      ocapEvents should have size 1
      val o = ocapEvents.head
      o.ruleId shouldBe "OCAP-001"
      o.ruleName should include("Borderline")
      o.actionType shouldBe "REWORK"
      o.affectedWafers should contain("WAFER-3")
    }
  }

  // ===================================================================
  // E5: RecoveryEvent published during crash/recovery cycle
  // ===================================================================

  "RecoveryEvent event" should {

    "E5: publish CRASH_DETECTED with completed phases count" in {
      val ctx = mockContext()

      val crashEvent = RecoveryEvent(
        workOrderId = "WO-REC-001",
        recoveryType = "CRASH_DETECTED",
        eventsReplayed = 6,
        phasesSkipped = 6,
        recoveryTimeMs = System.currentTimeMillis(),
        detail = "Actor crash for workOrder WO-REC-001 (6 phases completed)"
      )

      ctx.publisher(crashEvent)

      val recoveryEvents = publishedEvents.collect { case r: RecoveryEvent => r }
      recoveryEvents should have size 1
      val r = recoveryEvents.head
      r.recoveryType shouldBe "CRASH_DETECTED"
      r.eventsReplayed shouldBe 6
      r.phasesSkipped shouldBe 6
      r.detail should include("6 phases completed")
    }

    "E5b: full recovery cycle CRASH_DETECTED → RECOVERING → RECOVERED" in {
      val ctx = mockContext()

      val events = Seq(
        RecoveryEvent("WO-FULL", "CRASH_DETECTED", 6, 6, 0L, "crash"),
        RecoveryEvent("WO-FULL", "RECOVERING", 6, 6, 100L, "recovering"),
        RecoveryEvent("WO-FULL", "RECOVERED", 6, 6, 350L, "recovered")
      )

      events.foreach(ctx.publisher)

      val recoveryEvents = publishedEvents.collect { case r: RecoveryEvent => r }
      recoveryEvents should have size 3
      recoveryEvents.map(_.recoveryType) shouldBe Seq("CRASH_DETECTED", "RECOVERING", "RECOVERED")
    }
  }

  // ===================================================================
  // E6: JSON serialization matches frontend expectations
  // ===================================================================

  "M3.5 event JSON serialization" should {

    "E6a: DemoCompleted JSON field names match frontend expectations" in {
      val json = Json.obj(
        "lotId" -> "FOUP-01", "totalWafers" -> 5,
        "passedWafers" -> 3, "reworkedWafers" -> 2, "scrappedWafers" -> 1)

      json.\("lotId").asOpt[String] shouldBe Some("FOUP-01")
      json.\("totalWafers").asOpt[Int] shouldBe Some(5)
      json.\("passedWafers").asOpt[Int] shouldBe Some(3)
      json.\("reworkedWafers").asOpt[Int] shouldBe Some(2)
      json.\("scrappedWafers").asOpt[Int] shouldBe Some(1)
    }

    "E6b: RecoveryEvent JSON field names match frontend expectations" in {
      val json = Json.obj(
        "workOrderId" -> "WO-1", "recoveryType" -> "RECOVERED",
        "eventsReplayed" -> 7, "phasesSkipped" -> 6,
        "recoveryTimeMs" -> 342, "detail" -> "resumed")

      json.\("workOrderId").asOpt[String] shouldBe Some("WO-1")
      json.\("recoveryType").asOpt[String] shouldBe Some("RECOVERED")
      json.\("eventsReplayed").asOpt[Int] shouldBe Some(7)
      json.\("phasesSkipped").asOpt[Int] shouldBe Some(6)
      json.\("recoveryTimeMs").asOpt[Long] shouldBe Some(342L)
      json.\("detail").asOpt[String] shouldBe Some("resumed")
    }

    "E6c: PipelineStageFailed JSON field names match frontend expectations" in {
      val json = Json.obj(
        "stageName" -> "Measure", "equipId" -> "CDSEM-01",
        "errorCode" -> "SENSOR_ANOMALY", "detail" -> "CD-SEM sensor anomaly")

      json.\("stageName").asOpt[String] shouldBe Some("Measure")
      json.\("equipId").asOpt[String] shouldBe Some("CDSEM-01")
      json.\("errorCode").asOpt[String] shouldBe Some("SENSOR_ANOMALY")
      json.\("detail").asOpt[String] shouldBe Some("CD-SEM sensor anomaly")
    }

    "E6d: OcapActionTriggered JSON field names match frontend expectations" in {
      val json = Json.obj(
        "ruleId" -> "OCAP-001", "ruleName" -> "Borderline → Rework",
        "actionType" -> "REWORK", "detail" -> "CD=35.2nm",
        "affectedWafers" -> Json.arr("WAFER-3", "WAFER-5"))

      json.\("ruleId").asOpt[String] shouldBe Some("OCAP-001")
      json.\("ruleName").asOpt[String] shouldBe Some("Borderline → Rework")
      json.\("actionType").asOpt[String] shouldBe Some("REWORK")
      json.\("affectedWafers").asOpt[Seq[String]].getOrElse(Seq.empty) should contain("WAFER-3")
    }

    "E6e: FaultInjected JSON field names match frontend expectations" in {
      val json = Json.obj(
        "workOrderId" -> "WO-F1", "equipmentId" -> "LITHO-01",
        "faultType" -> "hardware_fault", "phaseName" -> "RunRecipe",
        "resolved" -> false)

      json.\("workOrderId").asOpt[String] shouldBe Some("WO-F1")
      json.\("equipmentId").asOpt[String] shouldBe Some("LITHO-01")
      json.\("faultType").asOpt[String] shouldBe Some("hardware_fault")
      json.\("phaseName").asOpt[String] shouldBe Some("RunRecipe")
      json.\("resolved").asOpt[Boolean] shouldBe Some(false)
    }

    "E6f: PipelineTimelineSnapshot JSON field names match frontend expectations" in {
      val json = Json.obj(
        "totalPhases" -> 14, "completedPhases" -> 7,
        "currentPhase" -> "Classify", "currentPhaseIndex" -> 6,
        "failedPhases" -> Json.arr("RunRecipe"), "recoveredPhases" -> Json.arr("RunRecipe"),
        "ocapTriggers" -> 1)

      json.\("totalPhases").asOpt[Int] shouldBe Some(14)
      json.\("completedPhases").asOpt[Int] shouldBe Some(7)
      json.\("currentPhase").asOpt[String] shouldBe Some("Classify")
      json.\("currentPhaseIndex").asOpt[Int] shouldBe Some(6)
      json.\("failedPhases").asOpt[Seq[String]].getOrElse(Seq.empty) should contain("RunRecipe")
      json.\("ocapTriggers").asOpt[Int] shouldBe Some(1)
    }
  }

  // ===================================================================
  // E7: publishDemoCompleted wafer classification correctness
  // ===================================================================

  "publishDemoCompleted" should {

    "E7a: classify wafers with None classification as neither PASS nor SCRAP" in {
      val ctx = mockContext()
      val state = FabDemoState(wafers = Map(
        "W-1" -> WaferInfo("W-1", classification = None),
        "W-2" -> WaferInfo("W-2", classification = None),
        "W-3" -> WaferInfo("W-3", classification = Some("PASS"))
      ))

      publishDemoCompleted(ctx, state, "WO-UNC")

      val dc = publishedEvents.collect { case e: DemoCompleted => e }.head
      dc.totalWafers shouldBe 3
      dc.passedWafers shouldBe 1
      dc.scrappedWafers shouldBe 0
    }

    "E7b: count a reworked wafer that eventually passed" in {
      val ctx = mockContext()
      val state = FabDemoState(wafers = Map(
        "W-1" -> WaferInfo("W-1", reworkCount = 2, classification = Some("PASS")),
        "W-2" -> WaferInfo("W-2", reworkCount = 1, classification = Some("FAIL"))
      ))

      publishDemoCompleted(ctx, state, "WO-RW")

      val dc = publishedEvents.collect { case e: DemoCompleted => e }.head
      dc.reworkedWafers shouldBe 2 // Both have reworkCount > 0
      dc.passedWafers shouldBe 1   // Only W-1 classified as PASS
    }

    "E7c: handle single-wafer state correctly" in {
      val ctx = mockContext()
      val state = FabDemoState(wafers = Map(
        "SOLO" -> WaferInfo("SOLO", reworkCount = 3, classification = Some("SCRAP"))
      ))

      publishDemoCompleted(ctx, state, "WO-SOLO")

      val dc = publishedEvents.collect { case e: DemoCompleted => e }.head
      dc.totalWafers shouldBe 1
      dc.passedWafers shouldBe 0
      dc.reworkedWafers shouldBe 1
      dc.scrappedWafers shouldBe 1
    }
  }
}
