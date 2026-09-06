package net.imadz.m25.component

import net.imadz.monarch.{LifecycleHooks, Monarch, RunRegistry}

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, Recovery}
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * ===== M2.5+ ChainExecutionActor =====
 *
 * 用 Akka Persistent EventSourcedBehavior 包装 Monarch 引擎驱动的 BankChain 六阶段流水线。
 *
 * == 为什么需要这个 Actor ==
 *
 * BankChain 是纯 Future 队列（monarch-core Monarch 引擎驱动），本身不持久化、不自愈。
 * ChainExecutionActor 在流水线外层提供：
 *   - 事件溯源：每个阶段完成持久化为 PhaseDone 事件（携带状态快照，完整审计追踪）
 *   - 自愈恢复：RecoveryCompleted 后 resumeFromIndex 跳过已完成阶段，只重跑断点之后
 *   - 水平扩展：通过 ClusterSharding EntityTypeKey 注册，分片自动分配
 *   - 世代守卫：StartExecution / RecoveryCompleted 各注册一个 RunRegistry 世代，
 *     崩溃前的旧 Future 链在下一个阶段边界静默终止，不再与新链并发
 *
 * == 恢复推演 ==
 *
 * 假设流水线执行到 poll 阶段时集群宕机：
 *
 * Journal 中已持久化:
 *   Started("recharge", "batch-001", 1)
 *   PhaseDone("file-gen", snapshot=Some(state1))
 *   PhaseDone("upload",   snapshot=Some(state2))
 *   PhaseDone("wait-ack", snapshot=Some(state3))
 *   ← 宕机，没有 Completed 或 Failed
 *
 * 恢复过程:
 * 1. Cluster 重启，Sharding 将 entity 分配到 node-3
 * 2. Akka Persistence 从 MongoDB 回放事件
 * 3. state = Executing(batchId="batch-001", completedPhases=3, lastState=state3)
 * 4. RecoveryCompleted 触发 → RunRegistry 注册新世代（旧链在下一阶段边界终止）
 *    → itemLoader 重新加载 items（与 lastState 中的快照合并，items 以 loader 为准）
 *    → monarch.resumeFromIndex(state, 3)：跳过 file-gen/upload/wait-ack，
 *      从 poll 断点续跑（各阶段幂等契约不变）
 * 5. persist Completed → stop
 *
 * == 与 M2 Persistent FSM 的对比 ==
 *
 * M2: 每业务有专用事件类型（RechargeRequestFileGenerated vs PurchaseFileGenerated）
 *      30+ Java 文件，充值/申购各 7 个 Actor
 * M2.5+: 通用事件类型（PhaseDone("file-gen", ...) 复用）
 *        1 个 ChainExecutionActor 服务所有业务链路
 *        业务差异完全在 ErrorCodeMapping / ReBatchPolicy 等配置中
 */
object ChainExecutionActor {

  val EntityKey: EntityTypeKey[Command] =
    EntityTypeKey[Command]("m25-chain-executor")

  // ============================================================
  // Protocol
  // ============================================================

  sealed trait Command
  sealed trait Event
  sealed trait State
  sealed trait ExecutionReply

  /** 启动流水线执行 */
  final case class StartExecution(
      batchId: String,
      items: List[Any],
      replyTo: ActorRef[ExecutionReply]
  ) extends Command

  /** 流水线阶段完成（BankChain 钩子回调发送的内部命令），携带阶段后置状态快照。 */
  private[component] final case class PhaseCompleted(
      phase: String,
      metadata: Map[String, String],
      snapshot: Option[BankChainState[Any, Any]] = None
  ) extends Command

  /** 整个流水线执行成功（内部命令） */
  private[component] case object PipelineSucceeded extends Command

  /** 流水线执行失败（内部命令） */
  private[component] final case class PipelineFailed(
      phase: String,
      reason: String
  ) extends Command

  /** 崩溃注入（自愈演示）：抛异常让 sharding 重启实体，触发断点续跑。 */
  final case class StopPipeline(reason: String) extends Command

  /** Host observer — demo/UI hook fired at run boundaries. Journaling stays internal;
    * the observer never influences control flow. All methods no-op by default. */
  trait ChainExecutionObserver {
    def onStart(batchId: String, itemCount: Int): Unit = ()
    def onStageStart(cursor: String): Unit = ()
    def onStageComplete(cursor: String, metadata: Map[String, String],
                        snapshot: Option[BankChainState[Any, Any]]): Unit = ()
    def onStageFailed(cursor: String, detail: String): Unit = ()
    def onRecovery(batchId: String, completedPhases: Int): Unit = ()
    def onCompleted(batchId: String, snapshot: Option[BankChainState[Any, Any]]): Unit = ()
    def onFailed(batchId: String, phase: String, reason: String): Unit = ()
    def onCrash(batchId: String, reason: String): Unit = ()
  }
  object ChainExecutionObserver {
    val nop: ChainExecutionObserver = new ChainExecutionObserver {}
  }

  // ---- Events ----

  final case class Started(
      batchId: String,
      chainId: String,
      itemCount: Int
  ) extends Event

  /** PhaseDone 携带阶段后置状态快照：恢复时 resumeFromIndex 需要断点处的中间值
    * （例如 upload 已完成时 receipt 必须在，否则 wait-ack/poll 无从执行）。 */
  final case class PhaseDone(
      phase: String,
      timestamp: Long,
      metadata: Map[String, String],
      snapshot: Option[BankChainState[Any, Any]] = None
  ) extends Event

  final case class AllCompleted(
      batchId: String,
      successCount: Int,
      failureCount: Int,
      suspiciousCount: Int
  ) extends Event

  final case class ExecutionFailed(
      phase: String,
      reason: String
  ) extends Event

  // ---- States ----

  case object Idle extends State

  final case class Executing(
      batchId: String,
      chainId: String,
      completedPhases: List[String],
      lastState: Option[BankChainState[Any, Any]] = None
  ) extends State {
    def lastCompletedPhase: Option[String] = completedPhases.lastOption
  }

  final case class Completed(
      batchId: String,
      successCount: Int,
      failureCount: Int
  ) extends State

  final case class Failed(
      phase: String,
      reason: String
  ) extends State

  // ---- Replies ----

  final case class Accepted(entityId: String) extends ExecutionReply
  final case class ExecutionSuccess(
      batchId: String,
      successCount: Int,
      failureCount: Int
  ) extends ExecutionReply
  final case class ExecutionRejected(
      batchId: String,
      phase: String,
      reason: String
  ) extends ExecutionReply

  // ============================================================
  // Factory
  // ============================================================

  /**
   * 创建 ChainExecutionActor 的 Behavior。
   *
   * 流水线执行由 monarch-core 的 Monarch 引擎驱动（BankChain 六阶段队列）：
   *   - StartExecution / RecoveryCompleted 各注册一个 RunRegistry 世代，崩溃前的旧
   *     Future 链在下一个阶段边界静默终止，不再与新链并发（对齐 Fab 侧 P0 修复）
   *   - RecoveryCompleted 通过 resumeFromIndex 跳过已完成阶段，从 PhaseDone 快照断点续跑
   *
   * @param chainId     业务链路标识（"recharge" / "purchase" / "equipment-area-3"）
   * @param pipeline    编译好的 SubBatchPipeline
   * @param itemLoader  从 batchId 重新加载 items 的函数（恢复时使用）
   */
  def apply(
      chainId: String,
      pipeline: SubBatchPipeline[Any, Any],
      itemLoader: String => Future[Seq[Any]],
      observer: ChainExecutionObserver = ChainExecutionObserver.nop
  )(implicit ec: ExecutionContext): Behavior[Command] = {

    Behaviors.setup { actorContext =>

      val persistenceId = PersistenceId(EntityKey.name, s"$chainId")

      /** Build a generation-guarded engine for one run. Journaling hooks re-check the
        * token at send time — a superseded chain must not journal anything. */
      def monarchFor(runToken: () => Boolean): Monarch[BankStage, BankChainState[Any, Any]] =
        BankChain.monarch(pipeline, hooks = new LifecycleHooks[BankStage, BankChainState[Any, Any]] {
          override def stageName(stage: BankStage): String = BankStage.stageName(stage)
          override def onStageStart(cursor: String): Unit =
            if (runToken()) observer.onStageStart(cursor)
          override def onStageComplete(cursor: String, state: BankChainState[Any, Any], metadata: Map[String, String]): Unit =
            if (runToken()) {
              observer.onStageComplete(cursor, metadata, Some(state))
              state.lastStage.foreach { stage =>
                actorContext.self ! PhaseCompleted(
                  BankStage.stageName(stage), BankChain.metadataOf(state), Some(state))
              }
            }
          override def onStageFailed(cursor: String, error: net.imadz.monarch.StageError): Unit =
            if (runToken()) {
              observer.onStageFailed(cursor, error.detail)
              actorContext.log.warn(
                s"[ChainExecutionActor:$chainId] Stage failed at {}: {}",
                cursor, error.detail)
            }
        }, runToken = runToken)

      def runBatch(batchId: String, items: Seq[Any], skip: Int, snapshot: Option[BankChainState[Any, Any]], runToken: () => Boolean): Unit = {
        val monarch = monarchFor(runToken)
        monarch.initialize(BankStage.chain)
        // 恢复时快照优先（含已完成的中间值），items 以 loader 结果为准（最新事实）
        val resumeState = snapshot.fold(BankChainState[Any, Any](
          batchId = batchId, chainId = chainId, items = items,
          context = Map("batchId" -> batchId, "chainId" -> chainId)))(s => s.copy(items = items))
        monarch.resumeFromIndex(resumeState, skip).onComplete {
          case Success(_) =>
            if (runToken()) actorContext.self ! PipelineSucceeded
          case Failure(e) =>
            if (runToken()) actorContext.self ! PipelineFailed("pipeline",
              Option(e.getMessage).getOrElse(e.toString))
        }
        ()
      }

      EventSourcedBehavior[Command, Event, State](
        persistenceId = persistenceId,
        emptyState = Idle,
        commandHandler = commandHandler(chainId, itemLoader, actorContext, observer, runBatch _),
        eventHandler = eventHandler
      ).withRecovery(Recovery.default)
        .receiveSignal {
          case (state: Executing, RecoveryCompleted) =>
            // 宕机恢复：注册新世代（旧链下一边界终止）→ 快照 + 计数断点续跑
            implicit val ec: ExecutionContext = actorContext.executionContext
            val key = s"$chainId-${state.batchId}"
            val generation = RunRegistry.register(key)
            val runToken: () => Boolean = () => RunRegistry.isFresh(key, generation)
            observer.onRecovery(state.batchId, state.completedPhases.size)
            itemLoader(state.batchId).onComplete {
              case Success(items) =>
                runBatch(state.batchId, items, state.completedPhases.size,
                  state.lastState, runToken)
              case Failure(e) =>
                actorContext.self ! PipelineFailed("recovery", s"Failed to reload items: ${e.getMessage}")
            }(actorContext.executionContext)

          case _ => ()
        }
    }
  }

  // ============================================================
  // Command Handler
  // ============================================================

  private def commandHandler(
      chainId: String,
      itemLoader: String => Future[Seq[Any]],
      ctx: ActorContext[Command],
      observer: ChainExecutionObserver,
      runBatch: (String, Seq[Any], Int, Option[BankChainState[Any, Any]], () => Boolean) => Unit
  )(state: State, cmd: Command)(implicit ec: ExecutionContext): Effect[Event, State] = {

    (state, cmd) match {

      // ---- Start execution ----
      case (Idle, StartExecution(batchId, itemsRaw, replyTo)) =>
        val event = Started(batchId, chainId, itemsRaw.size)
        Effect.persist(event).thenRun { _ =>
          // 注册世代：任何此前的旧链（理论上 Idle 态没有，防御性兜底）即刻失效
          val generation = RunRegistry.register(s"$chainId-$batchId")
          val runToken: () => Boolean = () => RunRegistry.isFresh(s"$chainId-$batchId", generation)
          observer.onStart(batchId, itemsRaw.size)
          runBatch(batchId, itemsRaw, 0, None, runToken)
          replyTo ! Accepted(s"$chainId-$batchId")
        }

      // ---- Phase completed callback from the engine ----
      case (_: Executing, PhaseCompleted(phase, metadata, snapshot)) =>
        Effect.persist(PhaseDone(phase, System.currentTimeMillis(), metadata, snapshot))

      // ---- Pipeline fully done ----
      case (_: Executing, PipelineSucceeded) =>
        val exec = state.asInstanceOf[Executing]
        // （实际统计数据从 read-side 查询，这里沿用占位值）
        Effect.persist(AllCompleted(exec.batchId, 0, 0, 0))
          .thenRun { _ =>
            observer.onCompleted(exec.batchId, exec.lastState)
            ctx.log.info(
              s"[ChainExecutionActor:$chainId] Batch {} completed. Phases: {}",
              exec.batchId,
              exec.completedPhases.mkString(" → "))
          }

      // ---- Pipeline failed ----
      case (_: Executing, PipelineFailed(phase, reason)) =>
        val exec = state.asInstanceOf[Executing]
        Effect.persist(ExecutionFailed(phase, reason))
          .thenRun { _ =>
            observer.onFailed(exec.batchId, phase, reason)
            ctx.log.error(
              s"[ChainExecutionActor:$chainId] Batch {} failed at phase {}: {}",
              exec.batchId, phase, reason
            )
          }

      // ---- Crash injection (self-healing demo) ----
      case (_: Executing, StopPipeline(reason)) =>
        Effect.none.thenRun { _ =>
          val exec = state.asInstanceOf[Executing]
          RunRegistry.register(s"$chainId-${exec.batchId}") // 旧链即刻失效（对齐 Fab P0）
          observer.onCrash(exec.batchId, reason)
          ctx.log.warn(s"[ChainExecutionActor:$chainId] Crash injected: {}", reason)
          throw new RuntimeException(s"Chain crash injected for batch ${exec.batchId}: $reason")
        }

      case (Idle, StopPipeline(reason)) =>
        Effect.none.thenRun { _ =>
          observer.onCrash("n/a", reason)
          ctx.log.warn(s"[ChainExecutionActor:$chainId] Crash injected in Idle: {}", reason)
          throw new RuntimeException(s"Chain crash injected (Idle): $reason")
        }

      // ---- Ignore late phase callbacks after completion ----
      case (_: Completed, PhaseCompleted(_, _, _)) =>
        Effect.none

      case (_: Failed, PhaseCompleted(_, _, _)) =>
        Effect.none

      // ---- Already idle/completed/failed, reject new Start ----
      case (s, StartExecution(batchId, _, replyTo)) =>
        ctx.log.warn(
          s"[ChainExecutionActor:$chainId] Rejecting StartExecution for batch {} in state {}",
          batchId, s.getClass.getSimpleName
        )
        replyTo ! ExecutionRejected(batchId, "start", s"state=${s.getClass.getSimpleName}")
        Effect.none

      case _ =>
        Effect.unhandled
    }
  }

  // ============================================================
  // Event Handler
  // ============================================================

  private val eventHandler: (State, Event) => State = { (state, event) =>
    (state, event) match {
      case (Idle, Started(batchId, chainId, _)) =>
        Executing(batchId, chainId, completedPhases = Nil)

      case (e: Executing, PhaseDone(phase, _, _, snapshot)) =>
        e.copy(completedPhases = e.completedPhases :+ phase, lastState = snapshot)

      case (e: Executing, AllCompleted(batchId, s, f, _)) =>
        Completed(batchId, s, f)

      case (_: Executing, ExecutionFailed(phase, reason)) =>
        Failed(phase, reason)

      // Idempotent replay: duplicate PhaseDone after AllCompleted
      case (_: Completed, PhaseDone(_, _, _, _)) =>
        state

      case _ =>
        state
    }
  }

  // ============================================================
  // Public: convenience factory for sharding registration
  // ============================================================

  import akka.cluster.sharding.typed.scaladsl.Entity

  /** Register the entity type. ONE ENTITY PER BATCH: pass the batch-unique entity id
    * (entityRefFor(EntityKey, s"$prefix-$batchId")) — the entityId IS the chainId and
    * the journal is per-entity, so batches never replay each other's events. */
  def init(
      sharding: akka.cluster.sharding.typed.scaladsl.ClusterSharding,
      pipeline: SubBatchPipeline[Any, Any],
      itemLoader: String => Future[Seq[Any]],
      observer: ChainExecutionObserver = ChainExecutionObserver.nop
  )(implicit ec: ExecutionContext): Unit = {
    initFor(sharding, pipelineFor = _ => pipeline, itemLoaderFor = itemLoader, observerFor = _ => observer)
  }

  /** Per-entityId dispatch variant: one entity type, many chains — pipeline / itemLoader /
    * observer are chosen by the entityId prefix (e.g. "bank-recharge-..." vs "bank-purchase-..."). */
  def initFor(
      sharding: akka.cluster.sharding.typed.scaladsl.ClusterSharding,
      pipelineFor: String => SubBatchPipeline[Any, Any],
      itemLoaderFor: String => Future[Seq[Any]],
      observerFor: String => ChainExecutionObserver
  )(implicit ec: ExecutionContext): Unit = {
    sharding.init(
      Entity(EntityKey) { entityContext =>
        val id = entityContext.entityId
        apply(id, pipelineFor(id), itemLoaderFor, observerFor(id))
      }
    )
  }
}
