package net.imadz.m25.template

import akka.actor.typed.Behavior
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey

/**
 * Resilience pattern assigned to an FSM node.
 */
sealed trait ResiliencePattern
object ResiliencePattern {
  /** Supervises child actors; restarts them on failure (blue). */
  case object Orchestrator  extends ResiliencePattern
  /** Guards a resource with timeout invariants (amber). */
  case object Protector     extends ResiliencePattern
  /** Polls external systems with active verification (purple). */
  case object Communicator  extends ResiliencePattern
  /** Scans for dead letters and re-injects them (red). */
  case object Compensator   extends ResiliencePattern
  /** Auxiliary service (teal). */
  case object Support       extends ResiliencePattern
}

/**
 * One FSM node in the generated DAG subgraph.
 *
 * @param entityKey   Akka Cluster Sharding EntityTypeKey for this FSM
 * @param behavior    The EventSourcedBehavior factory
 * @param label       Human-readable label (e.g. "充值请求")
 * @param pattern     Resilience pattern for visualization & monitoring
 * @param level       DAG level (used for layout)
 */
final case class FSMNode(
    entityKey: EntityTypeKey[_],
    behavior:  Behavior[_],
    label:     String,
    pattern:   ResiliencePattern,
    level:     Int
)

/**
 * A directed edge between two FSM nodes in the generated DAG.
 *
 * @param from     Source node entity key name
 * @param to       Target node entity key name
 * @param label    Edge label for visualization (e.g. "imports", "on-success")
 * @param feedback True if this edge feeds back to an earlier level (dashed line)
 * @param external True if this edge crosses into an external gateway
 */
final case class DAGEdge(
    from:     String,
    to:       String,
    label:    String = "",
    feedback: Boolean = false,
    external: Boolean = false
)

/**
 * The materialized output of a FlowTemplate.
 *
 * Contains everything needed to wire the generated FSMs into the
 * Akka Cluster Sharding infrastructure:
 *   - nodes:  EntityTypeKey + Behavior pairs to register with ClusterSharding
 *   - edges:  DAG topology for visualization and monitoring
 */
final case class DAGSubgraph(
    nodes: Seq[FSMNode],
    edges: Seq[DAGEdge]
)
