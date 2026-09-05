package net.imadz.monarch

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class RunRegistrySpec extends AnyWordSpec with Matchers {

  "RunRegistry" should {

    "treat unknown keys as fresh (first run on a fresh JVM)" in {
      RunRegistry.isFresh("wo-unknown", 42) should be(true)
    }

    "make a registered generation the only fresh one for its key" in {
      val gen1 = RunRegistry.register("wo-a")
      RunRegistry.isFresh("wo-a", gen1) should be(true)

      val gen2 = RunRegistry.register("wo-a") // recovery / restart bumps the generation
      RunRegistry.isFresh("wo-a", gen2) should be(true)
      RunRegistry.isFresh("wo-a", gen1) should be(false) // pre-crash chain is now stale
    }

    "keep generations independent per key" in {
      val a = RunRegistry.register("wo-iso-a")
      val b = RunRegistry.register("wo-iso-b")
      RunRegistry.isFresh("wo-iso-a", a) should be(true)
      RunRegistry.isFresh("wo-iso-b", b) should be(true)
      RunRegistry.register("wo-iso-a") // bumping a must not touch b
      RunRegistry.isFresh("wo-iso-b", b) should be(true)
    }

    "hand out strictly increasing generations" in {
      val g1 = RunRegistry.register("wo-mono")
      val g2 = RunRegistry.register("wo-mono")
      g2 should be > g1
    }
  }
}
