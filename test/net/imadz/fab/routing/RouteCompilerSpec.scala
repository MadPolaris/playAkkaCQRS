package net.imadz.fab.routing

import net.imadz.fab.chain.FabScenarioPipeline._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RouteCompilerSpec extends AnyFlatSpec with Matchers {

  "RouteValidator" should "accept a valid basic linear route" in {
    val route = basicLinearRoute
    val result = RouteValidator.validate(route)
    result.isValid shouldBe true
  }

  it should "reject duplicate node IDs" in {
    val dupNode = AtomicStep("n1", "Load Duplicate", LoadFoupOp)
    val route = basicLinearRoute.copy(nodes = dupNode :: basicLinearRoute.nodes)
    val result = RouteValidator.validate(route)
    result.isValid shouldBe false
    result.errors.exists(_.contains("Duplicate")) shouldBe true
  }

  it should "detect edges referencing missing nodes" in {
    val badEdge = RouteEdge("bad", "n1", "no-such-node")
    val route = basicLinearRoute.copy(edges = basicLinearRoute.edges :+ badEdge)
    val result = RouteValidator.validate(route)
    result.errors.exists(_.contains("not found")) shouldBe true
  }

  "RouteCompiler" should "compile a basic linear route to correct PipelineStage sequence" in {
    val route = basicLinearRoute
    val stages = RouteCompiler.compile(route)

    stages should have length 9
    stages(0) shouldBe LoadFoup
    stages(1) shouldBe Transport("STOCKER", "LITHO")
    stages(2) shouldBe AtEquipment("LITHO", "LITHO-01")
    stages(3) shouldBe RunRecipe("LITHO-01", "LITHO-28-001")
    stages(4) shouldBe Transport("LITHO", "CDSEM")
    stages(5) shouldBe AtEquipment("METROLOGY", "CDSEM-01")
    stages(6) shouldBe Measure("CDSEM-01")
    stages(7) shouldBe Classify
    stages(8) shouldBe SealComplete
  }

  it should "compile a route with a DecisionNode" in {
    val route = routeWithDecision
    val stages = RouteCompiler.compile(route)

    // Should contain a Branch stage
    stages.exists(_.isInstanceOf[Branch]) shouldBe true

    val branch = stages.collectFirst { case b: Branch => b }.get
    // true branch: Transport + Seal
    branch.ifTrue should have length 2
    branch.ifTrue.head shouldBe Transport("STOCKER", "LITHO")
    // false branch: Scrap + Seal
    branch.ifFalse should have length 2
  }

  it should "compile a route with OCAP node" in {
    val route = routeWithOcap
    val stages = RouteCompiler.compile(route)

    stages.exists(_.isInstanceOf[OcapEvaluate]) shouldBe true
  }

  it should "round-trip: basic linear route compiled output matches expected stages" in {
    val route = basicLinearRoute
    val compiled = RouteCompiler.compile(route)

    val expected = Seq(
      LoadFoup,
      Transport("STOCKER", "LITHO"),
      AtEquipment("LITHO", "LITHO-01"),
      RunRecipe("LITHO-01", "LITHO-28-001"),
      Transport("LITHO", "CDSEM"),
      AtEquipment("METROLOGY", "CDSEM-01"),
      Measure("CDSEM-01"),
      Classify,
      SealComplete
    )

    compiled shouldBe expected
  }

  // ============================================================================
  // Test route fixtures
  // ============================================================================

  private def basicLinearRoute: RouteDefinition = RouteDefinition(
    routeId = "test-basic",
    productId = "TEST-001",
    version = 1,
    name = "Basic Linear Route",
    nodes = List(
      AtomicStep("n1", "Load", LoadFoupOp),
      AtomicStep("n2", "Transport STOCKER→LITHO", TransportOp, Map("from" -> "STOCKER", "to" -> "LITHO")),
      AtomicStep("n3", "At Litho", AtEquipmentOp, Map("area" -> "LITHO", "equipId" -> "LITHO-01")),
      AtomicStep("n4", "Run Recipe", RunRecipeOp, Map("equipId" -> "LITHO-01", "recipeId" -> "LITHO-28-001")),
      AtomicStep("n5", "Transport LITHO→CDSEM", TransportOp, Map("from" -> "LITHO", "to" -> "CDSEM")),
      AtomicStep("n6", "At CDSEM", AtEquipmentOp, Map("area" -> "METROLOGY", "equipId" -> "CDSEM-01")),
      AtomicStep("n7", "Measure", MeasureOp, Map("equipId" -> "CDSEM-01")),
      AtomicStep("n8", "Classify", ClassifyOp),
      AtomicStep("n9", "Seal", SealCompleteOp)
    ),
    edges = (1 to 8).map { i =>
      RouteEdge(s"e$i", s"n$i", s"n${i + 1}")
    }.toList
  )

  private def routeWithDecision: RouteDefinition = RouteDefinition(
    routeId = "test-decision",
    productId = "TEST-002",
    version = 1,
    name = "Route with Decision",
    nodes = List(
      AtomicStep("n1", "Load", LoadFoupOp),
      DecisionNode("n2", "Pilot OK?", MeasurementCondition("cd_nm", WithinRange, 28.0, 34.0, AllWafers)),
      AtomicStep("n3", "Transport", TransportOp, Map("from" -> "STOCKER", "to" -> "LITHO")),
      AtomicStep("n4", "Seal Pass", SealCompleteOp),
      AtomicStep("n5", "Scrap", SealCompleteOp)
    ),
    edges = List(
      RouteEdge("e1", "n1", "n2"),
      RouteEdge("e2", "n2", "n3", MaterialFlow),
      RouteEdge("e3", "n3", "n4", MaterialFlow),
      RouteEdge("e4", "n2", "n5", ExceptionFlow),
      RouteEdge("e5", "n5", "n4", MaterialFlow)
    )
  )

  private def routeWithOcap: RouteDefinition = RouteDefinition(
    routeId = "test-ocap",
    productId = "TEST-003",
    version = 1,
    name = "Route with OCAP",
    nodes = List(
      AtomicStep("n1", "Load", LoadFoupOp),
      AtomicStep("n2", "Measure", MeasureOp, Map("equipId" -> "CDSEM-01")),
      OcapNode("ocap1", "OCAP Rules", List(
        OcapRuleDefinition("r1", "Borderline CD", MeasurementCondition("cd_nm", WithinRange, 34.0, 36.0, AnyWafer),
          OcapRework("REWORK-001", 2), priority = 1)
      )),
      AtomicStep("n3", "Seal", SealCompleteOp)
    ),
    edges = List(
      RouteEdge("e1", "n1", "n2"),
      RouteEdge("e2", "n2", "ocap1", OcapFlow),
      RouteEdge("e3", "n2", "n3", MaterialFlow)
    )
  )
}
