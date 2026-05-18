package net.imadz.fab.routing

import net.imadz.fab.events.{FabSimulationEvent, OcapActionTriggered}
import net.imadz.fab.model.FabExecutionModel.{FabDemoContext, FabDemoState, WaferInfo}
import net.imadz.fab.scenario.{DecisionConfig, StandardScenarios}
import net.imadz.application.aggregates.LotProtocol.{LotCommand, LotConfirmation}
import net.imadz.common.Id
import net.imadz.fab.protocol.ActorEquipmentAdapter
import akka.actor.typed.ActorRef
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

class OcapEngineSpec extends AnyFlatSpec with Matchers {

  implicit val ec: ExecutionContext = ExecutionContext.global

  def makeState(wafers: Map[String, WaferInfo]): FabDemoState =
    FabDemoState(wafers = wafers)

  def wafer(cd: Double, classification: String = "PASS"): WaferInfo =
    WaferInfo(
      waferId = s"W-${cd.toInt}",
      cdValueHistory = List(cd),
      classification = Some(classification)
    )

  def rule(id: String, cond: ConditionExpression, action: OcapActionPlan, priority: Int = 0): OcapRuleDefinition =
    OcapRuleDefinition(ruleId = id, name = s"Rule $id", triggerCondition = cond, actionPlan = action, priority = priority)

  // ---- Condition evaluation ----

  "ConditionEvaluator" should "evaluate MeasurementCondition with WithinRange" in {
    val state = makeState(Map("W1" -> wafer(32.0)))
    val cond = MeasurementCondition("cd_nm", WithinRange, 28.0, 34.0, AllWafers)
    ConditionEvaluator.evaluate(cond, state) shouldBe true
  }

  it should "reject MeasurementCondition when value is out of range" in {
    val state = makeState(Map("W1" -> wafer(38.0)))
    val cond = MeasurementCondition("cd_nm", WithinRange, 28.0, 34.0, AllWafers)
    ConditionEvaluator.evaluate(cond, state) shouldBe false
  }

  it should "evaluate AnyWafer scope with mixed values" in {
    val state = makeState(Map("W1" -> wafer(32.0), "W2" -> wafer(38.0)))
    val cond = MeasurementCondition("cd_nm", GreaterThan, 35.0, 0.0, AnyWafer)
    ConditionEvaluator.evaluate(cond, state) shouldBe true
  }

  it should "evaluate AggregateCondition with AND logic" in {
    val state = makeState(Map("W1" -> wafer(32.0)))
    val cond = AggregateCondition(List(
      MeasurementCondition("cd_nm", GreaterThan, 28.0, 0.0, AllWafers),
      MeasurementCondition("cd_nm", LessThan, 34.0, 0.0, AllWafers)
    ), And)
    ConditionEvaluator.evaluate(cond, state) shouldBe true
  }

  it should "reject AggregateCondition with AND when one fails" in {
    val state = makeState(Map("W1" -> wafer(38.0)))
    val cond = AggregateCondition(List(
      MeasurementCondition("cd_nm", GreaterThan, 28.0, 0.0, AllWafers),
      MeasurementCondition("cd_nm", LessThan, 34.0, 0.0, AllWafers)
    ), And)
    ConditionEvaluator.evaluate(cond, state) shouldBe false
  }

  it should "evaluate AggregateCondition with OR logic" in {
    val state = makeState(Map("W1" -> wafer(32.0)))
    val cond = AggregateCondition(List(
      MeasurementCondition("cd_nm", GreaterThan, 38.0, 0.0, AllWafers),
      MeasurementCondition("cd_nm", WithinRange, 28.0, 34.0, AllWafers)
    ), Or)
    ConditionEvaluator.evaluate(cond, state) shouldBe true
  }

  it should "evaluate OutsideRange correctly" in {
    val state = makeState(Map("W1" -> wafer(38.0)))
    val cond = MeasurementCondition("cd_nm", OutsideRange, 28.0, 34.0, AllWafers)
    ConditionEvaluator.evaluate(cond, state) shouldBe true
  }

  it should "return false for empty wafer set" in {
    val state = makeState(Map.empty)
    val cond = MeasurementCondition("cd_nm", WithinRange, 28.0, 34.0, AllWafers)
    ConditionEvaluator.evaluate(cond, state) shouldBe false
  }

  // ---- OcapEngine rule matching ----

  "OcapEngine.matchRules" should "return empty when no rules match" in {
    val state = makeState(Map("W1" -> wafer(32.0)))
    val rules = List(
      rule("R1", MeasurementCondition("cd_nm", GreaterThan, 38.0, 0.0, AllWafers), OcapScrap("too high"))
    )
    OcapEngine.matchRules(state, rules) shouldBe empty
  }

  it should "match a single triggered rule" in {
    val state = makeState(Map("W1" -> wafer(38.0)))
    val rules = List(
      rule("R1", MeasurementCondition("cd_nm", GreaterThan, 35.0, 0.0, AllWafers), OcapScrap("out of spec"))
    )
    val matched = OcapEngine.matchRules(state, rules)
    matched should have size 1
    matched.head.ruleId shouldBe "R1"
  }

  it should "sort matched rules by priority (lower = higher priority)" in {
    val state = makeState(Map("W1" -> wafer(38.0)))
    val cond = MeasurementCondition("cd_nm", GreaterThan, 35.0, 0.0, AllWafers)
    val rules = List(
      rule("R-low", cond, OcapNotify("low", "eng"), priority = 5),
      rule("R-high", cond, OcapScrap("critical"), priority = 0),
      rule("R-mid", cond, OcapRework("REWORK-001", 2), priority = 2)
    )
    val matched = OcapEngine.matchRules(state, rules)
    matched.map(_.ruleId) shouldBe List("R-high", "R-mid", "R-low")
  }

  it should "match multiple rules on same condition" in {
    val state = makeState(Map("W1" -> wafer(42.0)))
    val cond1 = MeasurementCondition("cd_nm", GreaterThan, 35.0, 0.0, AnyWafer)
    val cond2 = MeasurementCondition("cd_nm", OutsideRange, 28.0, 34.0, AnyWafer)
    val rules = List(
      rule("R1", cond1, OcapScrap("far out"), priority = 0),
      rule("R2", cond2, OcapNotify("oos", "eng"), priority = 1)
    )
    val matched = OcapEngine.matchRules(state, rules)
    matched should have size 2
  }

  // ---- OcapEngine.evaluate with event publishing ----

  "OcapEngine.evaluate" should "publish OcapActionTriggered event for matched rule" in {
    val events = ListBuffer.empty[FabSimulationEvent]
    val state = makeState(Map("W1" -> wafer(38.0)))
    val rules = List(
      rule("OCAP-001", MeasurementCondition("cd_nm", GreaterThan, 35.0, 0.0, AnyWafer),
        OcapScrap("CD out of spec"), priority = 0)
    )

    val ctx = mockContext(e => events += e)
    await(OcapEngine.evaluate(state, ctx, rules))

    events.exists(_.isInstanceOf[OcapActionTriggered]) shouldBe true
    val triggered = events.collect { case e: OcapActionTriggered => e }.head
    triggered.ruleId shouldBe "OCAP-001"
    triggered.actionType shouldBe "SCRAP"
    triggered.affectedWafers should contain("W1")
  }

  it should "not publish event when no rule matches" in {
    val events = ListBuffer.empty[FabSimulationEvent]
    val state = makeState(Map("W1" -> wafer(32.0)))
    val rules = List(
      rule("OCAP-001", MeasurementCondition("cd_nm", GreaterThan, 38.0, 0.0, AllWafers),
        OcapScrap("out of spec"))
    )

    val ctx = mockContext(e => events += e)
    await(OcapEngine.evaluate(state, ctx, rules))

    events.exists(_.isInstanceOf[OcapActionTriggered]) shouldBe false
  }

  it should "publish events for all triggered rules" in {
    val events = ListBuffer.empty[FabSimulationEvent]
    val state = makeState(Map("W1" -> wafer(42.0)))
    val rules = List(
      rule("R1", MeasurementCondition("cd_nm", GreaterThan, 35.0, 0.0, AnyWafer), OcapScrap("far"), priority = 0),
      rule("R2", MeasurementCondition("cd_nm", OutsideRange, 28.0, 34.0, AnyWafer), OcapNotify("oos", "eng"), priority = 1)
    )

    val ctx = mockContext(e => events += e)
    await(OcapEngine.evaluate(state, ctx, rules))

    val ocapEvents = events.collect { case e: OcapActionTriggered => e }
    ocapEvents should have size 2
    ocapEvents.map(_.ruleId) should contain allElementsOf Seq("R1", "R2")
  }

  // ---- SpecRepository ----

  "SpecRepository" should "return registered spec by product ID" in {
    SpecRepository.clear("TEST-PROD")
    val config = DecisionConfig(30.0, 36.0, 2.0)
    SpecRepository.register("TEST-PROD", config)
    SpecRepository.getLatest("TEST-PROD") shouldBe Some(config)
    SpecRepository.clear("TEST-PROD")
  }

  it should "return latest version" in {
    SpecRepository.clear("TEST-PROD")
    val v1 = DecisionConfig(28.0, 34.0, 2.0)
    val v2 = DecisionConfig(27.0, 33.0, 1.5)
    SpecRepository.register("TEST-PROD", v1, 1)
    SpecRepository.register("TEST-PROD", v2, 2)
    SpecRepository.getLatest("TEST-PROD") shouldBe Some(v2)
    SpecRepository.clear("TEST-PROD")
  }

  it should "return specific version" in {
    SpecRepository.clear("TEST-PROD")
    val v1 = DecisionConfig(28.0, 34.0, 2.0)
    SpecRepository.register("TEST-PROD", v1, 1)
    SpecRepository.get("TEST-PROD", 1) shouldBe Some(v1)
    SpecRepository.get("TEST-PROD", 99) shouldBe None
    SpecRepository.clear("TEST-PROD")
  }

  it should "list versions in order" in {
    SpecRepository.clear("TEST-PROD")
    SpecRepository.register("TEST-PROD", DecisionConfig(28.0, 34.0, 2.0), 1)
    SpecRepository.register("TEST-PROD", DecisionConfig(27.0, 33.0, 2.0), 3)
    SpecRepository.register("TEST-PROD", DecisionConfig(29.0, 35.0, 2.0), 2)
    SpecRepository.listVersions("TEST-PROD") shouldBe List(1, 2, 3)
    SpecRepository.clear("TEST-PROD")
  }

  it should "have default specs registered" in {
    SpecRepository.getLatest("PHOTOCELL-5WAFER") shouldBe defined
    SpecRepository.getLatest("LOGIC-28NM-A") shouldBe defined
  }

  it should "return None for unknown product" in {
    SpecRepository.getLatest("UNKNOWN-PROD") shouldBe None
  }

  // ---- Helpers ----

  def await[T](f: Future[T]): T = Await.result(f, 10.seconds)

  /** Factory for minimal FabDemoContext — only publisher and ec are used by OcapEngine. */
  def mockContext(publisher: FabSimulationEvent => Unit): FabDemoContext = FabDemoContext(
    scenario = StandardScenarios.photoCell5Wafer,
    foupId = "FOUP-001",
    lotRef = null.asInstanceOf[akka.cluster.sharding.typed.scaladsl.EntityRef[LotCommand]],
    reworkLotRef = null.asInstanceOf[akka.cluster.sharding.typed.scaladsl.EntityRef[LotCommand]],
    waferUUIDs = Map.empty,
    sourceLotId = Id.of("00000000-0000-0000-0000-000000000001"),
    reworkLotId = Id.of("00000000-0000-0000-0000-000000000002"),
    adapter = null.asInstanceOf[ActorEquipmentAdapter],
    publisher = publisher,
    ignoreLotReply = null.asInstanceOf[ActorRef[LotConfirmation]],
    sagaTx = (_, _, _, _, _) => Future.successful(null),
    speedMultiplier = 1.0
  )(ec)
}
