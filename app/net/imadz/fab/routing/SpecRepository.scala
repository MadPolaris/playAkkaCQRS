package net.imadz.fab.routing

import net.imadz.fab.scenario.DecisionConfig
import java.util.concurrent.ConcurrentHashMap
import scala.collection.JavaConverters._

/**
 * Versioned specification repository for manufacturing specs (CD limits, rework policy).
 *
 * Independent from RouteDefinition storage — specs change on a different cadence
 * than process routes. Keyed by productId -> version, with latest-version lookup.
 *
 * Thread-safe via ConcurrentHashMap.
 */
object SpecRepository {

  // productId -> (version -> DecisionConfig)
  private val specs = new ConcurrentHashMap[String, ConcurrentHashMap[Int, DecisionConfig]]()

  /** Register a spec for a product at a given version. */
  def register(productId: String, config: DecisionConfig, version: Int = 1): Unit = {
    val versions = specs.computeIfAbsent(productId, _ => new ConcurrentHashMap[Int, DecisionConfig]())
    versions.put(version, config)
  }

  /** Get the latest version of a spec for a product. */
  def getLatest(productId: String): Option[DecisionConfig] = {
    val versions = specs.get(productId)
    if (versions == null || versions.isEmpty) None
    else Some(versions.get(versions.keySet().asScala.max))
  }

  /** Get a specific version of a spec. */
  def get(productId: String, version: Int): Option[DecisionConfig] = {
    val versions = specs.get(productId)
    if (versions == null) None else Option(versions.get(version))
  }

  /** List all registered versions for a product. */
  def listVersions(productId: String): List[Int] = {
    val versions = specs.get(productId)
    if (versions == null) Nil else versions.keySet().asScala.toList.sorted
  }

  /** List all registered product IDs. */
  def listProducts(): List[String] =
    specs.keySet().asScala.toList.sorted

  /** Remove all specs for a product (for testing). */
  def clear(productId: String): Unit = {
    specs.remove(productId)
  }

  /** Remove all specs (for testing). */
  def clearAll(): Unit = {
    specs.clear()
  }

  // ---- Default specs registered at startup ----

  register("PHOTOCELL-5WAFER", DecisionConfig(
    lowerSpecNm = 28.0, upperSpecNm = 34.0, borderlineWindowNm = 2.0,
    maxReworkCount = 2, reworkRecipeId = "REWORK-LITHO-001"
  ))

  register("LOGIC-28NM-A", DecisionConfig(
    lowerSpecNm = 28.0, upperSpecNm = 34.0, borderlineWindowNm = 2.0,
    maxReworkCount = 3, reworkRecipeId = "REWORK-LITHO-001"
  ))
}
