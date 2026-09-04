package net.imadz.application.routing

import net.imadz.domain.routing._

import net.imadz.domain.routing._

import java.util.concurrent.ConcurrentHashMap
import net.imadz.application.chain.FabExecutionModel.FabDemoState

/**
 * Thread-safe metric registry for OCAP condition evaluation.
 *
 * Maps symbolic metric names (e.g. "cd_nm", "rework_count") to extractor
 * functions that pull the relevant data from [[FabDemoState]].
 *
 * Built-in metrics are registered at class-load time; additional metrics
 * can be injected at runtime via [[register]].
 *
 * Thread safety: backed by [[java.util.concurrent.ConcurrentHashMap]].
 */
object MetricResolver {

  type MetricExtractor = FabDemoState => Iterable[Double]

  private val registry = new ConcurrentHashMap[String, MetricExtractor]()

  // ── Built-in metrics ──────────────────────────────────────────────
  registry.put("cd_nm",        state => state.wafers.values.flatMap(_.cdValueHistory.lastOption))
  registry.put("rework_count", state => state.wafers.values.map(_.reworkCount.toDouble))

  /**
   * Resolve a named metric against the current [[FabDemoState]].
   *
   * @return [[Some]](values) if the metric is registered and non-empty, [[None]] otherwise.
   */
  def resolve(metric: String, state: FabDemoState): Option[Iterable[Double]] =
    Option(registry.get(metric)).map(_(state)).filter(_.nonEmpty)

  /**
   * Register a custom metric extractor at runtime.
   *
   * @param metric    the symbolic name (e.g. "thickness_nm")
   * @param extractor a pure function that reads the relevant values from [[FabDemoState]]
   */
  def register(metric: String, extractor: MetricExtractor): Unit =
    registry.put(metric, extractor)
}
