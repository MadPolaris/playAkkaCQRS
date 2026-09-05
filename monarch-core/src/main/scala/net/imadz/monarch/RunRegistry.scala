package net.imadz.monarch

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Generation registry for superseded-run cancellation, keyed by an arbitrary run key
  * (a work order id, a batch id, ...).
  *
  * Every new run of the same key `register`s — bumping the generation — and captures the
  * returned token into its `Monarch.runToken`. When a newer generation is registered, every
  * older run of that key turns stale at its next stage boundary and terminates silently
  * ([[StaleRun]]) instead of racing the new run. This closes the double-pipeline window
  * between "old run started a Future chain" and "crash recovery starts a new one" — the
  * same window that once let a stale chain journal events onto a restarted entity.
  *
  * Scope note: per-JVM. Cross-node moves additionally need a cluster-wide signal.
  */
object RunRegistry {

  // NOTE: must be java.lang.Long, NOT scala.Long — with the primitive type Scala
  // auto-unboxes get() (null → 0L), silently breaking the unknown-key == null check.
  private val generations = new ConcurrentHashMap[String, java.lang.Long]()
  private val counters = new AtomicLong(0)

  /** Registers a NEW generation for the key, invalidating all previous runs. */
  def register(key: String): Long = {
    val gen = counters.incrementAndGet()
    generations.put(key, gen)
    gen
  }

  /** True iff `gen` is still the current generation for the key. Unregistered keys are
    * fresh (nothing to be stale against). */
  def isFresh(key: String, gen: Long): Boolean = {
    val current = generations.get(key)
    current == null || current.longValue() == gen
  }
}

/** Control-flow signal: the run was superseded — terminate silently.
  * The engine's recover clauses check staleness FIRST and re-throw this,
  * so it never reaches the failure interceptor. */
object StaleRun extends RuntimeException("monarch run superseded by a newer generation")
