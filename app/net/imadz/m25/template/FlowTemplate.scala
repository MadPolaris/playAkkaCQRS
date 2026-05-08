package net.imadz.m25.template

/**
 * A parameterized FSM template.
 *
 * FlowTemplate is the core abstraction of M2.5.  A template captures the
 * *invariant structure* of a class of business flows (states, transitions,
 * resilience patterns, DAG topology) and exposes the *varying parts* as
 * explicit type-safe parameters.
 *
 * Design principle (Scaffold, not Framework):
 *   - `materialize()` returns standard Akka types (Behavior, EntityTypeKey).
 *   - Users can always break the glass and hand-modify generated FSMs.
 *   - No runtime interpretation — the result is plain Scala that the
 *     compiler type-checks.
 *
 * @tparam Params The parameter type that captures all variation points.
 */
trait FlowTemplate[Params] {

  /** Produce a fully materialized DAG subgraph from the given parameters. */
  def materialize(params: Params): DAGSubgraph
}
