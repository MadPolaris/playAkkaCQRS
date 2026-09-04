package net.imadz.m25.component

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
 * 用 Akka Persistent EventSourcedBehavior 包装 SubBatchProcessor 的 Future 流水线。
 *
 * == 为什么需要这个 Actor ==
 *
 * M2.5+ 的 SubBatchProcessor 是纯 Future 流水线，本身不持久化、不自愈。
 * ChainExecutionActor 在流水线外层提供：
 *   - 事件溯源：每个阶段完成持久化为 PhaseCompleted 事件（完整审计追踪）
 *   - 自愈恢复：集群宕机重启后，从 journal 回放事件，重新执行流水线
 *   - 水平扩展：通过 ClusterSharding EntityTypeKey 注册，分片自动分配
 *   - 幂等守卫：通知类阶段（SMS/P2B/额度释放）通过 read-side 去重
 *
 * == 恢复推演 ==
 *
 * 假设流水线执行到 poll 阶段时集群宕机：
 *
 * Journal 中已持久化:
 *   Started("recharge", "batch-001", items)
 *   PhaseCompleted("file-gen", ..., {localPath:"/tmp/x.txt"})
 *   PhaseCompleted("upload", ...,   {remotePath:"/remote/x.txt"})
 *   PhaseCompleted("wait-ack", ..., {ack:"received"})
 *   ← 宕机，没有 Completed 或 Failed
 *
 * 恢复过程:
 * 1. Cluster 重启，Sharding 将 entity 分配到 node-3
 * 2. Akka Persistence 从 MongoDB 回放 4 个事件
 * 3. state = Executing("batch-001", lastPhase=Some("wait-ack"), ...)
 * 4. RecoveryCompleted 触发 → 重新执行整个流水线
 *    - file-gen: 覆盖写入临时文件（幂等）
 *    - upload: 覆盖上传远程文件，相同 MD5（幂等）
 *    - wait-ack: 重新通知 EAMS（EAMS 处理重复通知）
 *    - poll: 重新轮询响应文件（可能已就绪）
 *    - parse/classify: 重新解析分类（幂等）
 * 5. notify 阶段：检查 MySQL read-side 是否已通知 → 跳过已完成的通知
 * 6. persist Completed → stop
 *
 * == 与 M2 Persistent FSM 的对比 ==
 *
 * M2: 每业务有专用事件类型（RechargeRequestFileGenerated vs PurchaseFileGenerated）
 *      30+ Java 文件，充值/申购各 7 个 Actor
 * M2.5+: 通用事件类型（PhaseCompleted("file-gen", ...) 复用）
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

  /** 流水线阶段完成（SubBatchProcessor 回调发送的内部命令） */
  private[component] final case class PhaseCompleted(
      phase: String,
      metadata: Map[String, String]
  ) extends Command

  /** 整个流水线执行成功（内部命令） */
  private[component] case object PipelineSucceeded extends Command

  /** 流水线执行失败（内部命令） */
  private[component] final case class PipelineFailed(
      phase: String,
      reason: String
  ) extends Command

  // ---- Events ----

  final case class Started(
      batchId: String,
      chainId: String,
      itemCount: Int
  ) extends Event

  final case class PhaseDone(
      phase: String,
      timestamp: Long,
      metadata: Map[String, String]
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
      // 已完成的阶段列表（按完成顺序）
      completedPhases: List[String]
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
   * @param chainId     业务链路标识（"recharge" / "purchase" / "equipment-area-3"）
   * @param pipeline    编译好的 SubBatchPipeline
   * @param itemLoader  从 batchId 重新加载 items 的函数（恢复时使用）
   */
  def apply[Item](
      chainId: String,
      pipeline: SubBatchPipeline[Item, Any],
      itemLoader: String => Future[Seq[Item]]
  )(implicit ec: ExecutionContext): Behavior[Command] = {

    Behaviors.setup { actorContext =>

      val processor = new SubBatchProcessor[Item, Any](
        pipeline = pipeline,
        onPhaseComplete = { (phase, metadata) =>
          actorContext.self ! PhaseCompleted(phase, metadata)
        }
      )

      val persistenceId = PersistenceId(EntityKey.name, s"$chainId")

      EventSourcedBehavior[Command, Event, State](
        persistenceId = persistenceId,
        emptyState = Idle,
        commandHandler = commandHandler(chainId, processor, itemLoader, actorContext),
        eventHandler = eventHandler
      ).withRecovery(Recovery.default)
        .receiveSignal {
          case (state: Executing, RecoveryCompleted) =>
            // 宕机恢复：重新执行流水线（各阶段幂等）
            itemLoader(state.batchId).onComplete {
              case Success(items) =>
                val batch = SubBatch[Item](
                  batchId = state.batchId,
                  items = items,
                  source = ItemSource.NewArrival,
                  context = Map("batchId" -> state.batchId, "chainId" -> state.chainId)
                )
                processor.process(batch).onComplete {
                  case Success(_)  => actorContext.self ! PipelineSucceeded
                  case Failure(e) => actorContext.self ! PipelineFailed("recovery", e.getMessage)
                }(actorContext.executionContext)
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

  private def commandHandler[Item](
      chainId: String,
      processor: SubBatchProcessor[Item, Any],
      itemLoader: String => Future[Seq[Item]],
      ctx: ActorContext[Command]
  )(state: State, cmd: Command)(implicit ec: ExecutionContext): Effect[Event, State] = {

    (state, cmd) match {

      // ---- Start execution ----
      case (Idle, StartExecution(batchId, itemsRaw, replyTo)) =>
        val event = Started(batchId, chainId, itemsRaw.size)
        Effect.persist(event).thenRun { _ =>
          val items = itemsRaw.asInstanceOf[List[Item]]
          val batch = SubBatch[Item](
            batchId = batchId,
            items = items.toSeq,
            source = ItemSource.NewArrival,
            context = Map("batchId" -> batchId, "chainId" -> chainId)
          )
          // 启动流水线（异步执行，阶段完成时通过 onPhaseComplete 回调通知）
          processor.process(batch).onComplete {
            case Success(_)  => ctx.self ! PipelineSucceeded
            case Failure(e) => ctx.self ! PipelineFailed("pipeline", e.getMessage)
          }
          replyTo ! Accepted(s"$chainId-$batchId")
        }

      // ---- Phase completed callback from processor ----
      case (_: Executing, PhaseCompleted(phase, metadata)) =>
        Effect.persist(PhaseDone(phase, System.currentTimeMillis(), metadata))

      // ---- Pipeline fully done ----
      case (_: Executing, PipelineSucceeded) =>
        // 读取最后一个 PhaseDone 来确定结果统计
        // （实际统计数据从 read-side 查询，这里用占位值）
        Effect.persist(AllCompleted(state.asInstanceOf[Executing].batchId, 0, 0, 0))
          .thenRun { s =>
            ctx.system.log.info(
              s"[ChainExecutionActor:$chainId] Batch {} completed successfully. Phases: {}",
              s.asInstanceOf[Completed].batchId,
              state.asInstanceOf[Executing].completedPhases.mkString(" → ")
            )
          }

      // ---- Pipeline failed ----
      case (_: Executing, PipelineFailed(phase, reason)) =>
        Effect.persist(ExecutionFailed(phase, reason))
          .thenRun { _ =>
            ctx.system.log.error(
              s"[ChainExecutionActor:$chainId] Batch {} failed at phase {}: {}",
              state.asInstanceOf[Executing].batchId, phase, reason
            )
          }

      // ---- Ignore late PhaseCompleted after completion ----
      case (_: Completed, PhaseCompleted(_, _)) =>
        Effect.none

      case (_: Failed, PhaseCompleted(_, _)) =>
        Effect.none

      // ---- Already idle/completed/failed, reject new Start ----
      case (s, StartExecution(batchId, _, _)) =>
        ctx.system.log.warn(
          s"[ChainExecutionActor:$chainId] Rejecting StartExecution for batch {} in state {}",
          batchId, s.getClass.getSimpleName
        )
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

      case (e: Executing, PhaseDone(phase, _, _)) =>
        e.copy(completedPhases = e.completedPhases :+ phase)

      case (e: Executing, AllCompleted(batchId, s, f, _)) =>
        Completed(batchId, s, f)

      case (_: Executing, ExecutionFailed(phase, reason)) =>
        Failed(phase, reason)

      // Idempotent replay: duplicate PhaseDone after AllCompleted
      case (_: Completed, PhaseDone(_, _, _)) =>
        state

      case _ =>
        state
    }
  }

  // ============================================================
  // Public: convenience factory for sharding registration
  // ============================================================

  import akka.cluster.sharding.typed.scaladsl.Entity

  def init[Item](
      sharding: akka.cluster.sharding.typed.scaladsl.ClusterSharding,
      chainId: String,
      pipeline: SubBatchPipeline[Item, Any],
      itemLoader: String => Future[Seq[Item]]
  )(implicit ec: ExecutionContext): Unit = {
    sharding.init(
      Entity(EntityKey) { entityContext =>
        apply(chainId, pipeline, itemLoader)
      }
    )
  }
}
