package net.imadz.fab.chain

import net.imadz.application.chain._

import net.imadz.fab.model.EquipmentArea; import net.imadz.domain.values.{Por, PorStep, PorRepository}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration._

class PorSpec extends AnyWordSpec with Matchers {

  "Por.validate" should {
    "pass for example routing (LOGIC-28NM-A)" in {
      Por.logic28nmPor.validate shouldBe Right(Por.logic28nmPor)
    }

    "reject empty steps" in {
      val r = Por("EMPTY-PROD", steps = Nil)
      r.validate.isLeft shouldBe true
    }

    "reject step with zero duration" in {
      val r = Por("BAD-PROD", steps = List(
        PorStep("op-001", EquipmentArea.Lithography, "REC-001", 0.millis)
      ))
      r.validate.isLeft shouldBe true
    }
  }

  "Por.areaVisitCounts" should {
    "count visits per area" in {
      val counts = Por.logic28nmPor.areaVisitCounts
      counts("LITHO") shouldBe 2 // one primary + one reentry
      counts("CLEAN") shouldBe 1
    }
  }

  "Por.hasReentry" should {
    "detect reentry for Lithography (appears twice)" in {
      Por.logic28nmPor.hasReentry("LITHO") shouldBe true
    }

    "not detect reentry for single-visit areas" in {
      Por.logic28nmPor.hasReentry("CLEAN") shouldBe false
    }
  }

  "Por.version" should {
    "default to 1" in {
      Por.logic28nmPor.version shouldBe 1
    }
  }

  "PorRepository" should {
    "find registered routing by product ID" in {
      val r = net.imadz.domain.values.PorRepository.findByProductId("LOGIC-28NM-A")
      r shouldBe defined
      r.get.steps.size shouldBe 11
    }

    "return None for unknown product" in {
      net.imadz.domain.values.PorRepository.findByProductId("UNKNOWN") shouldBe None
    }

    "list all registered products" in {
      val products = net.imadz.domain.values.PorRepository.listProducts
      products.map(_.productId) should contain("LOGIC-28NM-A")
    }
  }
}
