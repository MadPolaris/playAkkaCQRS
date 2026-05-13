package net.imadz.fab.chain

import net.imadz.fab.model.{EquipmentArea, ProductRouting, RoutingStep}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration._

class ProductRoutingSpec extends AnyWordSpec with Matchers {

  "ProductRouting.validate" should {
    "pass for example routing (LOGIC-28NM-A)" in {
      ProductRouting.exampleRouting.validate shouldBe Right(ProductRouting.exampleRouting)
    }

    "reject empty steps" in {
      val r = ProductRouting("EMPTY-PROD", steps = Nil)
      r.validate.isLeft shouldBe true
    }

    "reject step with zero duration" in {
      val r = ProductRouting("BAD-PROD", steps = List(
        RoutingStep("op-001", EquipmentArea.Lithography, "REC-001", 0.millis)
      ))
      r.validate.isLeft shouldBe true
    }
  }

  "ProductRouting.areaVisitCounts" should {
    "count visits per area" in {
      val counts = ProductRouting.exampleRouting.areaVisitCounts
      counts("LITHO") shouldBe 2 // one primary + one reentry
      counts("CLEAN") shouldBe 1
    }
  }

  "ProductRouting.hasReentry" should {
    "detect reentry for Lithography (appears twice)" in {
      ProductRouting.exampleRouting.hasReentry("LITHO") shouldBe true
    }

    "not detect reentry for single-visit areas" in {
      ProductRouting.exampleRouting.hasReentry("CLEAN") shouldBe false
    }
  }

  "ProductRouting.version" should {
    "default to 1" in {
      ProductRouting.exampleRouting.version shouldBe 1
    }
  }

  "ProductRoutingRepository" should {
    "find registered routing by product ID" in {
      val r = net.imadz.fab.model.ProductRoutingRepository.findByProductId("LOGIC-28NM-A")
      r shouldBe defined
      r.get.steps.size shouldBe 11
    }

    "return None for unknown product" in {
      net.imadz.fab.model.ProductRoutingRepository.findByProductId("UNKNOWN") shouldBe None
    }

    "list all registered products" in {
      val products = net.imadz.fab.model.ProductRoutingRepository.listProducts
      products.map(_.productId) should contain("LOGIC-28NM-A")
    }
  }
}
