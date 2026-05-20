package net.imadz.fab.chain

import net.imadz.application.chain._

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import net.imadz.application.chain.FabExecutionModel.{FabDemoContext, FabDemoState, StageError, StageFailedException, WaferInfo}
import net.imadz.fab.protocol.{ActorEquipmentAdapter, EquipmentCommand, EquipmentEvent, JobCompleted, JobFailed, MetrologyResult, CriticalDimension}
import net.imadz.domain.events.PipelineStageFailed
import net.imadz.application.scenario.{FabSimulationScenario, DecisionConfig}
import net.imadz.fab.simulation.{EquipmentConfig, LithoConfig, CdSemConfig, AmhsConfig, StockerConfig}
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.{ExecutionContext, Future}

class PipelineFailureSpec extends ScalaTestWithActorTestKit(
  ConfigFactory.parseString(
    """akka {
      |  extensions = ["net.imadz.common.serialization.SerializationExtension"]
      |  actor.provider = "cluster"
      |  remote.artery.canonical.port = 0
      |}""".stripMargin))
  with AnyWordSpecLike with BeforeAndAfterEach with BeforeAndAfterAll {

  private implicit val ec: ExecutionContext = system.executionContext
  private val sharding = akka.cluster.sharding.typed.scaladsl.ClusterSharding(system)

  private val events = scala.collection.mutable.ListBuffer.empty[Any]
  private def collectEvents: Any => Unit = e => events += e

  override def beforeAll(): Unit = {
    sharding.init(akka.cluster.sharding.typed.scaladsl.Entity(
      net.imadz.application.aggregates.LotAggregate.LotEntityTypeKey) { _ =>
      akka.actor.typed.scaladsl.Behaviors.ignore[net.imadz.application.aggregates.LotProtocol.LotCommand]
    })
  }

  /** Minimal mock context for PipelineStages testing */
  private def mockContext(adapter: ActorEquipmentAdapter): FabDemoContext = {
    val scenario = FabSimulationScenario(
      scenarioId = "test", name = "Test", description = "", lotSize = 1,
      waferIds = Seq("WAFER-1"),
      litho = EquipmentConfig("LITHO-01", "LITHO"),
      lithoDetail = LithoConfig(waferCount = 1),
      cdSem = EquipmentConfig("CDSEM-01", "METROLOGY"),
      cdSemDetail = CdSemConfig(waferIds = Seq("WAFER-1"), targetCdNm = 32.0, waferOutcomes = Map("WAFER-1" -> "PASS")),
      amhs = AmhsConfig(),
      stocker = StockerConfig("STOCKER-01"),
      decision = DecisionConfig(lowerSpecNm = 28.0, upperSpecNm = 32.0, borderlineWindowNm = 2.0, maxReworkCount = 2, reworkRecipeId = "REWORK-001")
    )
    val lotRef = sharding.entityRefFor(
      net.imadz.application.aggregates.LotAggregate.LotEntityTypeKey, "test-lot")
    FabDemoContext(
      scenario = scenario, foupId = "FOUP-TEST", lotRef = lotRef, reworkLotRef = lotRef,
      waferUUIDs = Map.empty, sourceLotId = java.util.UUID.randomUUID(), reworkLotId = java.util.UUID.randomUUID(),
      adapter = adapter, publisher = collectEvents.asInstanceOf[net.imadz.domain.events.FabSimulationEvent => Unit],
      ignoreLotReply = system.ignoreRef,
      sagaTx = (_, _, _, _, _) => Future.successful(
        net.imadz.application.services.transactor.FabSagaProtocol.FabSagaConfirmation(
          transactionId = java.util.UUID.randomUUID(), error = None)),
      speedMultiplier = 1.0
    )
  }

  private def initialState = FabDemoState(wafers = Map("WAFER-1" -> WaferInfo("WAFER-1")))

  override def afterEach(): Unit = events.clear()

  // ===================================================================
  // A1: PipelineStages.process() failure handling
  // ===================================================================

  "PipelineStages.process()" should {

    "return StageFailedException on JobFailed" in {
      val adapter = new ActorEquipmentAdapter()(system, ec) {
        override def sendCommand(equipmentId: String, cmd: EquipmentCommand): Future[EquipmentEvent] =
          Future.successful(JobFailed("JOB-1", equipmentId, "HARDWARE_FAULT", "Simulated hardware fault"))
      }
      val result = PipelineStages.process(initialState, mockContext(adapter), "LITHO-01", "LITHO-28-001", "LITHO")

      val ex = result.failed.futureValue.asInstanceOf[StageFailedException]
      ex.error.stageName shouldBe "Process"
      ex.error.equipId shouldBe Some("LITHO-01")
      ex.error.errorCode shouldBe "HARDWARE_FAULT"
      ex.error.detail should include("hardware fault")
    }

    "publish PipelineStageFailed event on JobFailed" in {
      val adapter = new ActorEquipmentAdapter()(system, ec) {
        override def sendCommand(equipmentId: String, cmd: EquipmentCommand): Future[EquipmentEvent] =
          Future.successful(JobFailed("JOB-1", equipmentId, "COMM_TIMEOUT", "Communication timed out"))
      }
      val result = PipelineStages.process(initialState, mockContext(adapter), "LITHO-01", "LITHO-28-001", "LITHO")
      result.failed.futureValue
      val failureEvents = events.collect { case e: PipelineStageFailed => e }
      failureEvents should have size 1
      failureEvents.head.stageName shouldBe "Process"
      failureEvents.head.errorCode shouldBe "COMM_TIMEOUT"
    }

    "return success Future when equipment returns JobCompleted" in {
      val adapter = new ActorEquipmentAdapter()(system, ec) {
        override def sendCommand(equipmentId: String, cmd: EquipmentCommand): Future[EquipmentEvent] =
          Future.successful(JobCompleted("JOB-1", equipmentId, MetrologyResult("JOB-1", Map.empty)))
      }
      val result = PipelineStages.process(initialState, mockContext(adapter), "LITHO-01", "LITHO-28-001", "LITHO")
      result.futureValue.ledgerSeq shouldBe initialState.ledgerSeq + 1
    }
  }

  // ===================================================================
  // A1: PipelineStages.measure() failure handling
  // ===================================================================

  "PipelineStages.measure()" should {

    "return StageFailedException on JobFailed" in {
      val adapter = new ActorEquipmentAdapter()(system, ec) {
        override def sendCommand(equipmentId: String, cmd: EquipmentCommand): Future[EquipmentEvent] =
          Future.successful(JobFailed("JOB-1", equipmentId, "SENSOR_ANOMALY", "CD-SEM sensor anomaly"))
      }
      val result = PipelineStages.measure(initialState, mockContext(adapter), "CDSEM-01")

      val ex = result.failed.futureValue.asInstanceOf[StageFailedException]
      ex.error.stageName shouldBe "Measure"
      ex.error.equipId shouldBe Some("CDSEM-01")
      ex.error.errorCode shouldBe "SENSOR_ANOMALY"
    }

    "return measurement data when CD-SEM succeeds" in {
      val adapter = new ActorEquipmentAdapter()(system, ec) {
        override def sendCommand(equipmentId: String, cmd: EquipmentCommand): Future[EquipmentEvent] =
          Future.successful(JobCompleted("JOB-1", equipmentId,
            MetrologyResult("JOB-1", Map("WAFER-1" -> CriticalDimension("WAFER-1", 32.0)))))
      }
      val result = PipelineStages.measure(initialState, mockContext(adapter), "CDSEM-01")
      result.futureValue.ledgerSeq shouldBe initialState.ledgerSeq + 1
    }
  }

  // ===================================================================
  // A1: StageError / StageFailedException structure
  // ===================================================================

  "StageError" should {
    "be correctly structured" in {
      val err = StageError("Process", Some("EQ-01"), "ERR-001", "detail message")
      err.stageName shouldBe "Process"
      err.equipId shouldBe Some("EQ-01")
      err.errorCode shouldBe "ERR-001"
      err.detail shouldBe "detail message"
    }
  }

  "StageFailedException" should {
    "wrap StageError and be a RuntimeException" in {
      val err = StageError("Test", Some("EQ-01"), "ERR-001", "test detail")
      val ex = StageFailedException(err)
      ex shouldBe a[RuntimeException]
      ex.getMessage should include("Test")
      ex.getMessage should include("ERR-001")
      ex.error shouldBe err
    }
  }
}
