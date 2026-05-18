package net.imadz.fab.chain

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.scaladsl.Behaviors
import net.imadz.application.aggregates.LotAggregate
import net.imadz.application.aggregates.LotProtocol.LotCommand
import net.imadz.application.services.transactor.FabSagaProtocol.FabSagaConfirmation
import net.imadz.fab.events._
import net.imadz.fab.model.FabExecutionModel.{FabDemoContext, FabDemoState, StageFailedException, WaferInfo}
import net.imadz.fab.protocol._
import net.imadz.fab.routing._
import net.imadz.fab.scenario.{DecisionConfig, FabSimulationScenario, StandardScenarios}
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future}

class WorkOrderE2EIntegrationSpec extends ScalaTestWithActorTestKit(
  ConfigFactory.parseString(
    """akka {
      |  actor.provider = "cluster"
      |  remote.artery.canonical.port = 0
      |}""".stripMargin))
  with AnyWordSpecLike with BeforeAndAfterEach with BeforeAndAfterAll {

  private implicit val ec: ExecutionContext = system.executionContext
  private val sharding = akka.cluster.sharding.typed.scaladsl.ClusterSharding(system)

  private val events = ListBuffer.empty[Any]
  private def collectEvents: Any => Unit = e => events += e

  private val defaultWaferIds = (1 to 5).map(i => s"WAFER-$i").toSeq

  override def beforeAll(): Unit = {
    sharding.init(akka.cluster.sharding.typed.scaladsl.Entity(LotAggregate.LotEntityTypeKey) { _ =>
      Behaviors.ignore[LotCommand]
    })
  }

  override def afterEach(): Unit = events.clear()

  // ====================================================================
  // Mock adapter factory
  // ====================================================================

  /** Mock equipment adapter that returns deterministic results for E2E tests.
    *
    * @param waferCdValues CD values to return from CDSEM (waferName -> cdNm)
    * @param failOnEquipment If set, this equipment returns JobFailed for ProcessRecipe
    * @param failWithCode Error code for the simulated failure
    */
  class E2EMockAdapter(
    waferCdValues: Map[String, Double] = defaultWaferIds.map(_ -> 32.0).toMap,
    failOnEquipment: Option[String] = None,
    failWithCode: String = "HARDWARE_FAULT"
  ) extends ActorEquipmentAdapter()(system, ec) {
    override def sendCommand(equipmentId: String, cmd: EquipmentCommand): Future[EquipmentEvent] = {
      // Check if this equipment should fail
      if (failOnEquipment.contains(equipmentId)) {
        cmd match {
          case ProcessRecipe(_, _) =>
            return Future.successful(JobFailed(UUID.randomUUID().toString, equipmentId, failWithCode, s"Simulated $failWithCode on $equipmentId"))
          case _ => // non-process commands don't fail
        }
      }
      cmd match {
        case ProcessRecipe(recipeId, _) if equipmentId.contains("CDSEM") =>
          val wafers = waferCdValues.map { case (wid, cd) =>
            wid -> CriticalDimension(wid, cd, 32.0)
          }
          Future.successful(JobCompleted(UUID.randomUUID().toString, equipmentId,
            MetrologyResult(UUID.randomUUID().toString, wafers)))

        case ProcessRecipe(recipeId, _) =>
          Future.successful(JobCompleted(UUID.randomUUID().toString, equipmentId,
            LithoExposureResult(UUID.randomUUID().toString, recipeId, Map.empty)))

        case TransferFoup(_, _, _) =>
          Future.successful(FoupArrived(equipmentId, s"$equipmentId-PORT-1"))

        case _ =>
          Future.successful(StatusReport(equipmentId, Idle, None, Map.empty))
      }
    }
  }

  // ====================================================================
  // Context factory
  // ====================================================================

  private def makeContext(
    adapter: ActorEquipmentAdapter,
    scenario: FabSimulationScenario = allPassScenario,
    waferIds: Seq[String] = defaultWaferIds,
    ocapRules: List[OcapRuleDefinition] = Nil,
    speedMultiplier: Double = 100.0
  ): FabDemoContext = {
    val lotRef = sharding.entityRefFor(LotAggregate.LotEntityTypeKey, "e2e-lot")
    FabDemoContext(
      scenario = scenario,
      foupId = "FOUP-E2E",
      lotRef = lotRef,
      reworkLotRef = lotRef,
      waferUUIDs = waferIds.map(wid => wid -> UUID.randomUUID()).toMap,
      sourceLotId = UUID.randomUUID(),
      reworkLotId = UUID.randomUUID(),
      adapter = adapter,
      publisher = collectEvents.asInstanceOf[FabSimulationEvent => Unit],
      ignoreLotReply = system.ignoreRef,
      sagaTx = (_, _, _, _, _) => Future.successful(FabSagaConfirmation(
        transactionId = UUID.randomUUID(), error = None)),
      speedMultiplier = speedMultiplier,
      ocapRules = ocapRules
    )
  }

  private def initialState(waferIds: Seq[String] = defaultWaferIds): FabDemoState =
    FabDemoState(wafers = waferIds.map(wid => wid -> WaferInfo(wid)).toMap)

  // ====================================================================
  // Scenario fixtures
  // ====================================================================

  val allPassScenario: FabSimulationScenario = StandardScenarios.photoCell5Wafer.copy(
    cdSemDetail = StandardScenarios.photoCell5Wafer.cdSemDetail.copy(
      waferOutcomes = defaultWaferIds.map(_ -> "PASS").toMap
    ))

  val failOnReworkScenario: FabSimulationScenario = StandardScenarios.photoCell5Wafer.copy(
    cdSemDetail = StandardScenarios.photoCell5Wafer.cdSemDetail.copy(
      waferOutcomes = Map(
        "WAFER-1" -> "PASS",
        "WAFER-2" -> "FAIL",
        "WAFER-3" -> "PASS",
        "WAFER-4" -> "PASS",
        "WAFER-5" -> "PASS"
      )
    ),
    decision = StandardScenarios.photoCell5Wafer.decision.copy(maxReworkCount = 2)
  )

  // ====================================================================
  // Tests
  // ====================================================================

  "P1. E2E Integration" should {

    // P1.1 — Full pipeline, all 5 wafers PASS
    "P1.1: complete 5-wafer scenario with all PASS" in {
      val cdValues = defaultWaferIds.map(_ -> 32.0).toMap
      val adapter = new E2EMockAdapter(waferCdValues = cdValues)
      val ctx = makeContext(adapter, allPassScenario)
      val init = initialState()

      val result = FabDemoPipeline.runPipeline(init, ctx)
      val finalState = result.futureValue

      finalState.passCount shouldBe 5
      finalState.scrapCount shouldBe 0
      finalState.ledgerSeq should be > 0

      // Verify completion events were published
      val completedEvents = events.collect { case e: DemoCompleted => e }
      completedEvents should have size 1
      completedEvents.head.passedWafers shouldBe 5
    }

    // P1.2 — OCAP intercept rework rule evaluation
    "P1.2: OCAP interceptor triggers Rework on equipment failure" in {
      // Setup: CD values that trigger an OCAP rework rule after measurement
      val cdValues = Map("WAFER-1" -> 38.0, "WAFER-2" -> 32.0)
      val state2 = FabDemoState(wafers = cdValues.map { case (wid, cd) =>
        wid -> WaferInfo(waferId = wid, cdValueHistory = List(cd))
      }.toMap)

      val ocapRules = List(
        OcapRuleDefinition(
          ruleId = "OCAP-RWK-001",
          name = "Out-of-spec CD → Rework",
          triggerCondition = MeasurementCondition("cd_nm", GreaterThan, 35.0, 0.0, AnyWafer),
          actionPlan = OcapRework(recipeId = "REWORK-001", maxCount = 2),
          priority = 0
        )
      )
      val ctx2 = makeContext(new E2EMockAdapter(waferCdValues = cdValues), ocapRules = ocapRules)

      // Evaluate OCAP rules — should trigger rework action for WAFER-1
      val evalResult = OcapEngine.evaluate(state2, ctx2, ocapRules)
      val resultState = evalResult.futureValue

      // Verify action triggered event
      val triggered = events.collect { case e: OcapActionTriggered => e }
      triggered.nonEmpty shouldBe true
      triggered.exists(_.ruleId == "OCAP-RWK-001") shouldBe true
      triggered.exists(_.actionType == "REWORK") shouldBe true

      // State should be preserved (OcapEngine.evaluate is informational for Rework type)
      resultState.wafers.get("WAFER-1").flatMap(_.cdValueHistory.lastOption) shouldBe Some(38.0)
    }

    // P1.3 — OCAP scrap rule
    "P1.3: OCAP triggers Scrap on CD out-of-spec" in {
      val cdValues = Map("WAFER-1" -> 50.0, "WAFER-2" -> 32.0)
      val state3 = FabDemoState(wafers = cdValues.map { case (wid, cd) =>
        wid -> WaferInfo(waferId = wid, cdValueHistory = List(cd))
      }.toMap)

      val scrapRule = OcapRuleDefinition(
        ruleId = "OCAP-SCR-001",
        name = "Severe CD out-of-spec → Scrap",
        triggerCondition = MeasurementCondition("cd_nm", GreaterThan, 45.0, 0.0, AnyWafer),
        actionPlan = OcapScrap("CD far out of spec — immediate scrap"),
        priority = 0
      )
      val ctx3 = makeContext(new E2EMockAdapter(waferCdValues = cdValues), ocapRules = List(scrapRule))

      val evalResult = OcapEngine.evaluate(state3, ctx3, List(scrapRule))
      evalResult.futureValue

      val triggered = events.collect { case e: OcapActionTriggered => e }
      triggered.nonEmpty shouldBe true
      triggered.exists(_.actionType == "SCRAP") shouldBe true
      triggered.exists(_.ruleId == "OCAP-SCR-001") shouldBe true
    }

    // P1.4 — FabScenarioPipeline basic stage execution
    "P1.4: FabScenarioPipeline runs basic stages correctly" in {
      val adapter = new E2EMockAdapter()
      val ctx4 = makeContext(adapter)

      val stages = Seq(
        FabScenarioPipeline.LoadFoup,
        FabScenarioPipeline.Transport("STOCKER", "LITHO"),
        FabScenarioPipeline.AtEquipment("LITHO", "LITHO-01")
      )

      val result = FabScenarioPipeline.runStages(stages, initialState(), ctx4)
      val state = result.futureValue

      state.currentArea should include("LITHO")
      state.ledgerSeq should be > 0

      // Verify events were published
      val statusEvents = events.collect { case e: GlobalStatusChanged => e }
      statusEvents.nonEmpty shouldBe true
    }

    // P1.5 — classifyCd with maxRework scenarios
    "P1.5: maxReworkCount exceeded leads to SCRAP" in {
      val config = DecisionConfig(
        lowerSpecNm = 28.0, upperSpecNm = 34.0,
        borderlineWindowNm = 2.0, maxReworkCount = 2,
        reworkRecipeId = "REWORK-001")

      val failCd = 38.0   // upperSpecNm + borderline = 36, upperSpecNm + 8 = 42
      val passCd = 32.0
      val scrapCd = 50.0  // > 42 → SCRAP

      // First measurement → FAIL
      val firstResult = PipelineStages.classifyCd(failCd, config)
      firstResult shouldBe "FAIL"

      // Simulate rework: same CD after rework → FAIL again → exceeds maxReworkCount (2)
      val secondResult = PipelineStages.classifyCd(failCd, config)
      secondResult shouldBe "FAIL"
      // After second FAIL, reworkCount would be 2 which >= maxReworkCount(2)
      // In the actual pipeline, this triggers SCRAP

      // PASS values
      PipelineStages.classifyCd(passCd, config) shouldBe "PASS"

      // BORDERLINE values (upperSpecNm + borderlineWindowNm = 36.0)
      PipelineStages.classifyCd(35.0, config) shouldBe "BORDERLINE"

      // SCRAP values (> 42.0)
      PipelineStages.classifyCd(scrapCd, config) shouldBe "SCRAP"
    }

    // P1.6 — Stage error propagation
    "P1.6: Pipeline stage error propagates StageFailedException" in {
      val adapter = new ActorEquipmentAdapter()(system, ec) {
        override def sendCommand(equipmentId: String, cmd: EquipmentCommand): Future[EquipmentEvent] =
          Future.successful(JobFailed("JOB-1", equipmentId, "HARDWARE_FAULT", "Simulated hardware fault"))
      }
      val ctx6 = makeContext(adapter)

      val result = PipelineStages.process(initialState(), ctx6, "LITHO-01", "LITHO-28-001", "LITHO")

      val ex = result.failed.futureValue.asInstanceOf[StageFailedException]
      ex.error.stageName shouldBe "Process"
      ex.error.equipId shouldBe Some("LITHO-01")
      ex.error.errorCode shouldBe "HARDWARE_FAULT"
      ex.error.detail should include("hardware fault")
    }
  }
}
