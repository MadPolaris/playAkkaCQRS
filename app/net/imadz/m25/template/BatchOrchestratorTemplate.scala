package net.imadz.m25.template

import akka.actor.typed.Behavior
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

import scala.concurrent.duration._

/**
 * Generic BatchOrchestrator template — covers the top-level orchestration layer.
 *
 * Generates up to 6 FSM nodes from a single parameter set:
 *   1. JobActor          — accepts cron trigger, fans out to pre-batch and re-batch
 *   2. PreBatchActor     — happy-path batch pre-processing
 *   3. ReBatchActor      — dead-letter scanner, re-injects failures into worker
 *   4. BatchMaster       — shards work across workers
 *   5. BatchWorker       — processes individual batch items (×N instances)
 *   6. BatchItemCreator  — splits jobs into individual batch items
 *
 * Resilience patterns:
 *   JobActor, PreBatch, BatchMaster, BatchWorker, BatchItem → Orchestrator
 *   ReBatchActor → Compensator
 *
 * Currently covers: job-actor, pre-batch, re-batch, batch-master, batch-worker, batch-item (6 FSM)
 */
final class BatchOrchestratorTemplate
  extends FlowTemplate[BatchOrchestratorTemplate.Params] {

  import BatchOrchestratorTemplate._

  override def materialize(p: Params): DAGSubgraph = {
    val job       = buildJobActorFSM(p)
    val preBatch  = buildPreBatchFSM(p)
    val reBatch   = buildReBatchFSM(p)
    val master    = buildBatchMasterFSM(p)
    val worker    = buildBatchWorkerFSM(p)
    val item      = buildBatchItemFSM(p)

    DAGSubgraph(
      nodes = Seq(job, preBatch, reBatch, master, worker, item),
      edges = Seq(
        DAGEdge(s"${p.prefix}-job",      s"${p.prefix}-prebatch"),
        DAGEdge(s"${p.prefix}-job",      s"${p.prefix}-rebatch"),
        DAGEdge(s"${p.prefix}-prebatch", s"${p.prefix}-batch-master"),
        DAGEdge(s"${p.prefix}-prebatch", s"${p.prefix}-batch-item"),
        DAGEdge(s"${p.prefix}-batch-master", s"${p.prefix}-batch-worker"),
        DAGEdge(s"${p.prefix}-batch-item",   s"${p.prefix}-batch-worker"),
        DAGEdge(s"${p.prefix}-rebatch", s"${p.prefix}-batch-worker", "re-inject", feedback = true)
      )
    )
  }

  private def buildJobActorFSM(p: Params): FSMNode = {
    val entityKey = EntityTypeKey[BatchCommand](s"${p.prefix}-job")
    val behavior = simpleFSM(entityKey, "作业编排器", ResiliencePattern.Orchestrator, level = 1)
    FSMNode(entityKey, behavior, s"${p.prefix}-job-actor", ResiliencePattern.Orchestrator, level = 1)
  }

  private def buildPreBatchFSM(p: Params): FSMNode = {
    val entityKey = EntityTypeKey[BatchCommand](s"${p.prefix}-prebatch")
    val behavior = simpleFSM(entityKey, "预批处理", ResiliencePattern.Orchestrator, level = 1)
    FSMNode(entityKey, behavior, s"${p.prefix}-pre-batch", ResiliencePattern.Orchestrator, level = 1)
  }

  private def buildReBatchFSM(p: Params): FSMNode = {
    val entityKey = EntityTypeKey[BatchCommand](s"${p.prefix}-rebatch")
    val behavior = simpleFSM(entityKey, "补偿扫描", ResiliencePattern.Compensator, level = 1)
    FSMNode(entityKey, behavior, s"${p.prefix}-re-batch", ResiliencePattern.Compensator, level = 1)
  }

  private def buildBatchMasterFSM(p: Params): FSMNode = {
    val entityKey = EntityTypeKey[BatchCommand](s"${p.prefix}-batch-master")
    val behavior = simpleFSM(entityKey, "批次分发", ResiliencePattern.Orchestrator, level = 2)
    FSMNode(entityKey, behavior, s"${p.prefix}-batch-master", ResiliencePattern.Orchestrator, level = 2)
  }

  private def buildBatchWorkerFSM(p: Params): FSMNode = {
    val entityKey = EntityTypeKey[BatchCommand](s"${p.prefix}-batch-worker")
    val behavior = simpleFSM(entityKey, "批次工人", ResiliencePattern.Orchestrator, level = 2)
    FSMNode(entityKey, behavior, s"${p.prefix}-batch-worker", ResiliencePattern.Orchestrator, level = 2)
  }

  private def buildBatchItemFSM(p: Params): FSMNode = {
    val entityKey = EntityTypeKey[BatchCommand](s"${p.prefix}-batch-item")
    val behavior = simpleFSM(entityKey, "明细创建", ResiliencePattern.Orchestrator, level = 2)
    FSMNode(entityKey, behavior, s"${p.prefix}-batch-item", ResiliencePattern.Orchestrator, level = 2)
  }

  private def simpleFSM(entityKey: EntityTypeKey[BatchCommand], label: String, pattern: ResiliencePattern, level: Int): Behavior[BatchCommand] =
    EventSourcedBehavior[BatchCommand, BatchEvent, BatchState](
      persistenceId = PersistenceId.of(entityKey.name, ""),
      emptyState    = BatchState.Idle,
      commandHandler = { (state, cmd) =>
        (state, cmd) match {
          case (BatchState.Idle, BatchCommand.Start(replyTo)) =>
            Effect.persist(BatchEvent.Started).thenRun { _ =>
              replyTo ! BatchReply.Started(entityKey.name)
            }
          case (BatchState.Running, BatchCommand.ChildCompleted(childId)) =>
            Effect.persist(BatchEvent.ChildDone(childId))
          case (BatchState.Running, BatchCommand.AllChildrenDone) =>
            Effect.persist(BatchEvent.Completed)
          case (BatchState.Running, BatchCommand.ChildFailed(childId, reason)) =>
            Effect.persist(BatchEvent.ChildFailed(childId, reason))
          case _ => Effect.unhandled
        }
      },
      eventHandler = { (state, event) =>
        (state, event) match {
          case (BatchState.Idle, BatchEvent.Started) => BatchState.Running
          case (BatchState.Running, BatchEvent.Completed) => BatchState.Completed
          case _ => state
        }
      }
    )
}

object BatchOrchestratorTemplate {
  final case class Params(
      prefix:          String,
      shardStrategy:   ShardStrategy = ShardStrategy.default,
      retryPolicy:     RetryPolicy   = RetryPolicy.default
  )

  final case class ShardStrategy(shards: Int, passivationAfter: FiniteDuration)
  object ShardStrategy {
    val default: ShardStrategy = ShardStrategy(100, 5.minutes)
  }

  final case class RetryPolicy(maxRetries: Int, backoff: FiniteDuration)
  object RetryPolicy {
    val default: RetryPolicy = RetryPolicy(3, 30.seconds)
  }

  sealed trait BatchCommand
  object BatchCommand {
    final case class Start(replyTo: akka.actor.typed.ActorRef[BatchReply]) extends BatchCommand
    final case class ChildCompleted(childId: String) extends BatchCommand
    final case class ChildFailed(childId: String, reason: String) extends BatchCommand
    case object AllChildrenDone extends BatchCommand
  }

  sealed trait BatchReply
  object BatchReply {
    final case class Started(fsmName: String) extends BatchReply
  }

  sealed trait BatchEvent
  object BatchEvent {
    case object Started extends BatchEvent
    final case class ChildDone(childId: String) extends BatchEvent
    final case class ChildFailed(childId: String, reason: String) extends BatchEvent
    case object Completed extends BatchEvent
  }

  sealed trait BatchState
  object BatchState {
    case object Idle extends BatchState
    case object Running extends BatchState
    case object Completed extends BatchState
  }
}
