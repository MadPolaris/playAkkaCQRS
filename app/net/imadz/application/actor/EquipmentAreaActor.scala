package net.imadz.application.actor

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.scaladsl.TimerScheduler
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.RecoveryCompleted
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import net.imadz.domain.events.{AreaStateChanged, EquipmentStateChanged, FabSimulationEvent}

import scala.concurrent.duration._

/**
 * 设备区状态机 Actor —— 每个工艺区一个分片 EventSourced 实例（本类是区域状态的唯一所有者）。
 *
 * 状态机（合法迁移之外的操作一律拒绝并回执原因）：
 *
 *   IDLE --TrackIn--> LOADED --StartProcess--> BUSY --ProcessDone(定时)--> FINISHED
 *     ^                                                                  |
 *     |<-- UnloadDone(定时) -- UNLOADING(出站中) <-- TrackOut <----------+
 *     |--- TrackOut（跳过量测的路径）<--- LOADED
 *     任意状态 --ReportFault--> DOWN --Reset--> IDLE
 *
 * 每个被接受的迁移都会由 Actor 自己通过 publisher 推送 AreaStateChanged 到前端 ——
 * 状态同步的所有权在区域 Actor，而不是散落在流水线代码里的旁白事件。
 * 崩溃恢复后 BUSY 不重跑计时器：视为已完成（FINISHED），由 trackOut 收尾。
 */
object EquipmentAreaActor {

  val EntityKey: EntityTypeKey[Command] = EntityTypeKey[Command]("EquipmentArea")

  // ---- 状态常量（与前端状态屏约定一致） ----
  val Idle = "IDLE"
  val Loaded = "LOADED"
  val Busy = "BUSY"
  val Finished = "FINISHED"
  val Unloading = "UNLOADING"
  val Down = "DOWN"

  private val DisplayName = Map(
    "CLEAN" -> "湿法清洗", "DIFF" -> "扩散/氧化", "LITHO" -> "光刻", "ETCH" -> "刻蚀",
    "IMPL" -> "离子注入", "DEP" -> "薄膜沉积", "CMP" -> "化学机械抛光", "MET" -> "量测",
    "DRY" -> "干燥", "STOCKER" -> "晶圆仓库", "LOG" -> "物流指挥")

  /** METROLOGY/CDSEM 等别名的规范 areaId */
  def canonical(area: String): String = area match {
    case "METROLOGY" | "CDSEM" => "MET"
    case other                 => other
  }

  // ============================================================
  // Protocol
  // ============================================================
  sealed trait Command
  final case class TrackIn(equipmentId: String, job: String, replyTo: Option[ActorRef[AreaReply]] = None) extends Command
  final case class StartProcess(equipmentId: String, recipe: String, durationMs: Long, replyTo: Option[ActorRef[AreaReply]] = None) extends Command
  final case class TrackOut(equipmentId: String, replyTo: Option[ActorRef[AreaReply]] = None) extends Command
  final case class ReportFault(equipmentId: String, code: String, detail: String) extends Command
  /** 加工完成信号：由管线的设备模拟器 JobCompleted 驱动（时钟源唯一） */
  final case class FinishProcess(equipmentId: String) extends Command
  case object Reset extends Command
  final case class GetState(replyTo: ActorRef[AreaSnapshot]) extends Command
  private case object ProcessWatchdog extends Command
  private case object UnloadDone extends Command
  /** 最短驻留门控的延迟重放载体（携带原始命令） */
  private case class DeferredCmd(inner: Command) extends Command

  final case class AreaReply(accepted: Boolean, status: String, reason: String)
  final case class AreaSnapshot(areaId: String, status: String, equipmentId: String, job: String)

  sealed trait Event
  final case class Transitioned(status: String, equipmentId: String, job: String, detail: String) extends Event

  final case class AreaState(status: String, equipmentId: String, job: String)

  // ============================================================
  // Sharding init + 静态注册表（PipelineStages 通过 entityRef 下发迁移命令）
  // ============================================================
  object Registry {
    @volatile private var shardingOpt: Option[ClusterSharding] = None

    /** 全部区域（含 STOCKER 仓库与 LOG 物流指挥） */
    val AllAreas: Seq[String] =
      Seq("STOCKER", "CLEAN", "DIFF", "LITHO", "ETCH", "IMPL", "DEP", "CMP", "MET", "DRY", "LOG")

    def init(sharding: ClusterSharding, publisher: FabSimulationEvent => Unit): Unit = {
      shardingOpt = Some(sharding)
      sharding.init(Entity(EntityKey)(ctx => apply(ctx.entityId, publisher)))
    }

    /** 未初始化（如纯 JVM 单测）返回 None，调用方静默跳过 —— 保持旧行为兼容 */
    def entityRef(areaId: String): Option[akka.cluster.sharding.typed.scaladsl.EntityRef[Command]] =
      shardingOpt.map(_.entityRefFor(EntityKey, canonical(areaId)))

    /** 用例启动时清场：所有区域复位到 IDLE（区域状态持久化、跨 run 共享，需显式清场） */
    def resetAll(): Unit =
      if (shardingOpt.isDefined) AllAreas.foreach { a => entityRef(a).foreach(_ ! Reset) }
  }

  def apply(areaId: String, publisher: FabSimulationEvent => Unit): Behavior[Command] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        eventSourced(areaId, publisher, timers, ctx)
      }
    }

  private def eventSourced(areaId: String, publisher: FabSimulationEvent => Unit,
                           timers: TimerScheduler[Command], ctx: ActorContext[Command]): Behavior[Command] =
    EventSourcedBehavior[Command, Event, AreaState](
      persistenceId = PersistenceId(EntityKey.name, areaId),
      emptyState = AreaState(Idle, "", ""),
      commandHandler = commandHandler(areaId, publisher, timers, ctx),
      eventHandler = {
        case (state, Transitioned(status, equipId, job, _)) =>
          AreaState(status, equipId, job)
      }
    ).receiveSignal {
      case (_, RecoveryCompleted) => // 状态由 eventHandler 恢复；BUSY 的计时器不持久化，见 commandHandler 恢复分支
      case (_, _) =>
    }
      .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 50, keepNSnapshots = 2))

  private def commandHandler(areaId: String, publisher: FabSimulationEvent => Unit,
                             timers: TimerScheduler[Command], ctx: ActorContext[Command]
                            ): (AreaState, Command) => Effect[Event, AreaState] = {
    // 状态最短驻留（演示节奏）：可见状态至少停留 MinStateDwellMs，过早到来的下一个迁移
    // 会被延迟执行。仅内存时间戳，actor 重启后重新起算（不影响状态正确性）。
    val MinStateDwellMs = 1800L
    var lastMoveAt = 0L

    def run(state: AreaState, cmd: Command): Effect[Event, AreaState] = {
      def accept(next: String, equipId: String, job: String, detail: String): Effect[Event, AreaState] =
        Effect.persist(Transitioned(next, equipId, job, detail)).thenRun { newState =>
          publish(publisher, areaId, newState.status, newState.equipmentId, newState.job, detail)
        }

      def reject(replyTo: Option[ActorRef[AreaReply]], reason: String): Effect[Event, AreaState] = {
        ctx.log.warn(s"[Area $areaId] command rejected: $reason (status=${state.status})")
        replyTo.foreach(_ ! AreaReply(accepted = false, state.status, reason))
        Effect.none
      }

      (state, cmd) match {
        case (_, TrackIn(equipId, job, replyTo)) if state.status == Loaded && state.job == job =>
          // 幂等：同一批重复 trackIn（崩溃恢复重放）直接接受，不再持久化
          replyTo.foreach(_ ! AreaReply(accepted = true, Loaded, "idempotent"))
          Effect.none

        case (_, TrackIn(_, _, replyTo)) if state.status == Busy || state.status == Finished =>
          reject(replyTo, s"cannot TrackIn while ${state.status}")

        case (_, TrackIn(equipId, job, replyTo)) if state.status == Down =>
          reject(replyTo, "area is DOWN (reset required)")

        case (_, TrackIn(equipId, job, replyTo)) =>
          replyTo.foreach(_ ! AreaReply(accepted = true, Loaded, ""))
          accept(Loaded, equipId, job, "trackIn")

        case (_, StartProcess(equipId, recipe, durationMs, replyTo)) if state.status != Loaded =>
          reject(replyTo, s"StartProcess requires LOADED (now ${state.status})")

        case (_, StartProcess(equipId, recipe, durationMs, replyTo)) =>
          replyTo.foreach(_ ! AreaReply(accepted = true, Busy, ""))
          // 看门狗：管线应在加工完成后发 FinishProcess；若信号丢失（宕机等）超时自动完成
          timers.startSingleTimer(ProcessWatchdog, (math.max(100, durationMs) + 8000).millis)
          accept(Busy, equipId, recipe, "processing")

        case (_, FinishProcess(equipId)) if state.status == Busy =>
          accept(Finished, state.equipmentId, state.job, "process done")

        case (_, ProcessWatchdog) if state.status == Busy =>
          ctx.log.warn(s"[Area $areaId] process watchdog fired — FinishProcess signal lost, auto-finishing")
          accept(Finished, state.equipmentId, state.job, "process done (watchdog)")

        case (_, TrackOut(equipId, replyTo)) if state.status == Loaded || state.status == Finished || state.status == Busy =>
          // 出站是有物理过程的：FOUP 从腔体搬回装载端口（UNLOADING），完成后设备才回到 IDLE 待机。
          // BUSY 下的 TrackOut 视为隐式完结加工（模拟器回调与管线线程跨发送者，FinishProcess 可能晚到）
          replyTo.foreach(_ ! AreaReply(accepted = true, Unloading, ""))
          timers.startSingleTimer(UnloadDone, 1500.millis)
          accept(Unloading, equipId, "",
            if (state.status == Busy) "trackOut (auto-finish busy job)" else "trackOut unloading")

        case (_, TrackOut(equipId, replyTo)) =>
          reject(replyTo, s"TrackOut requires LOADED/FINISHED (now ${state.status})")

        case (_, UnloadDone) if state.status == Unloading =>
          accept(Idle, state.equipmentId, "", "unload complete — equipment idle")

        case (_, ReportFault(equipId, code, detail)) =>
          accept(Down, equipId, state.job, s"$code: $detail")

        case (_, Reset) =>
          accept(Idle, state.equipmentId, "", "reset")

        case (_, GetState(replyTo)) =>
          replyTo ! AreaSnapshot(areaId, state.status, state.equipmentId, state.job)
          Effect.none

        case (_, ProcessWatchdog) => Effect.none // 非 BUSY 收到的迟到看门狗：忽略
      }
    }

    // 外层：最短驻留门控 —— 过早到来的可见迁移延迟到驻留期满；故障/复位/查询立即执行
    (state, cmd) => cmd match {
      case _: ReportFault | Reset | _: GetState =>
        lastMoveAt = System.currentTimeMillis()
        run(state, cmd)
      case _: TrackIn | _: StartProcess | _: FinishProcess | _: TrackOut =>
        val elapsed = System.currentTimeMillis() - lastMoveAt
        if (elapsed >= MinStateDwellMs) {
          lastMoveAt = System.currentTimeMillis()
          run(state, cmd)
        } else {
          timers.startSingleTimer(DeferredCmd(cmd), (MinStateDwellMs - elapsed).millis)
          Effect.none
        }
      case DeferredCmd(inner) =>
        lastMoveAt = System.currentTimeMillis()
        run(state, inner)
    }
  }

  private def publish(publisher: FabSimulationEvent => Unit, areaId: String,
                      status: String, equipId: String, job: String, detail: String): Unit = {
    publisher(AreaStateChanged(areaId, DisplayName.getOrElse(areaId, areaId), status, equipId, job, detail))
    // 兼容广播：2D 演示页等旧消费者仍监听 EquipmentStateChanged —— 状态值映射回旧词汇表
    val legacy = status match {
      case Loaded   => Some("Load")
      case Busy     => Some("Busy")
      case Finished => Some("Idle")  // 加工完成，设备空转等待卸料
      case Idle     => Some("Idle")
      case Down     => Some("DOWN")
      case _        => None          // UNLOADING 过渡态不向旧词汇表广播
    }
    if (equipId.nonEmpty) legacy.foreach { st =>
      publisher(EquipmentStateChanged(equipId, areaId, st, if (job.isEmpty) None else Some(job)))
    }
  }
}
