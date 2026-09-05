package net.imadz.application.chain

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * P0 staleness registry for pipeline runs, keyed by workOrderId.
 *
 * Every `StartExecution` / crash recovery bumps the generation for its workOrderId and
 * captures it into the run's [[FabDemoContext]].runToken. When a newer generation is
 * registered, the token of every older run of the same work order turns stale, so the
 * abandoned (pre-crash) Future chain terminates at the next stage boundary instead of
 * racing the recovered chain — previously it kept publishing UI events and driving
 * aggregate commands concurrently with the recovered run.
 *
 * Scope note: the registry is per-JVM (matches the demo's single-node crash injection).
 * Cross-node sharding moves would additionally need a cluster-wide signal.
 */
object PipelineRunRegistry {

  private val generations = new ConcurrentHashMap[String, Long]()
  private val counters = new AtomicLong(0)

  /** Registers a NEW generation for the work order, invalidating all previous runs. */
  def register(workOrderId: String): Long = {
    val gen = counters.incrementAndGet()
    generations.put(workOrderId, gen)
    gen
  }

  /** True iff `gen` is still the current generation for the work order. */
  def isFresh(workOrderId: String, gen: Long): Boolean = {
    val current = generations.get(workOrderId)
    current == null || current == gen
  }
}

/** Control-flow signal: the run was superseded — terminate silently. (The pipeline's
  * recover clauses check staleness FIRST and re-throw this, so it never triggers OCAP.) */
object StaleRun extends RuntimeException("pipeline run superseded by a newer generation")
