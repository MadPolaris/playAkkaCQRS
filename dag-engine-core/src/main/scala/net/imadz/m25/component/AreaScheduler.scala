package net.imadz.m25.component

import scala.concurrent.Future
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * 设备区调度器——Process Manager 模式。
 *
 * 接收来自上游的 items（新到达 + ReBatchRouter 路由来的），
 * 考虑物理约束重新成批，分派到设备。
 *
 * M2.5 薪资存入场景：物理约束简单（min=1, max=100, 无载体约束）。
 * M3 Fab 场景：约束关键（FOUP 必须 25 片、同 recipe 才能混批等）。
 */

sealed trait ItemSource
object ItemSource {
  /** 新到达的 items（首次处理） */
  case object NewArrival extends ItemSource
  /** 从指定设备区重路由来的 items */
  final case class ReBatch(fromArea: String) extends ItemSource
}

case class ScheduleResult(
    accepted: Int,
    rejected: Int,
    pendingBatchCount: Int
)

/** 物理约束——影响如何成批 */
case class PhysicalConstraints(
    /** 每批最小 item 数（不到此数可能需要等待更多 item 或强制发批） */
    minBatchSize: Int = 1,
    /** 每批最大 item 数 */
    maxBatchSize: Int = 100,
    /** 物理载体容量（如 FOUP 25片）。0 表示无物理载体约束 */
    carrierCapacity: Int = 0,
    /** 时间窗口：等多久再发批 */
    batchWindow: FiniteDuration = 10.minutes,
    /** 是否允许不同来源的 item 混批 */
    allowMixedSources: Boolean = true,
    /** 是否为强制成批（如不满 minBatchSize 也发） */
    forceBatchWhenFull: Boolean = true
)

/** 一个小批次——准备进入 SubBatchProcessor 处理 */
case class SubBatch[Item](
    batchId: String,
    items: Seq[Item],
    source: ItemSource,
    context: Map[String, Any] = Map.empty
)

/** 小批次处理结果 */
case class SubBatchResult[ClassifiedItem](
    batchId: String,
    successes: Seq[ClassifiedItem],
    failures: Seq[ClassifiedItem],
    suspicious: Seq[ClassifiedItem]
)

/**
 * 设备区调度器接口。
 *
 * submit + schedule 分离：
 *   - submit: 接收 items，放入等待队列
 *   - schedule: 根据 PhysicalConstraints 决定何时成批、成多大的批
 */
trait AreaScheduler[Item] {
  /** 提交 items 到调度器的等待队列 */
  def submit(items: Seq[Item], source: ItemSource): Future[ScheduleResult]

  /** 触发一次调度决策——从等待队列中取出 items 成批 */
  def schedule(): Future[Seq[SubBatch[Item]]]

  /** 当前等待队列中的 item 数 */
  def pendingCount: Int
}

/**
 * 简单的窗口式调度器实现。
 *
 * 策略：
 *   1. submit 将 items 追加到等待队列
 *   2. schedule 按 FIFO + maxBatchSize 截断成批
 *   3. 如果等待量 < minBatchSize 且最近一次 submit 在 batchWindow 内，等待
 *   4. 超过 batchWindow 强制发批
 */
abstract class WindowedAreaScheduler[Item](
    constraints: PhysicalConstraints
) extends AreaScheduler[Item] {

  private var waitingQueue: Vector[SubBatch[Item]] = Vector.empty
  private var lastSubmitTime: Long = 0L

  /** 生成唯一的批次 ID */
  def generateBatchId(): String

  override def submit(items: Seq[Item], source: ItemSource): Future[ScheduleResult] = {
    if (items.isEmpty)
      return Future.successful(ScheduleResult(0, 0, pendingCount))

    val now = System.currentTimeMillis()
    lastSubmitTime = now

    val batchId = generateBatchId()
    waitingQueue = waitingQueue :+ SubBatch(batchId, items, source)

    Future.successful(ScheduleResult(
      accepted = items.size, rejected = 0,
      pendingBatchCount = waitingQueue.size
    ))
  }

  override def schedule(): Future[Seq[SubBatch[Item]]] = {
    val now = System.currentTimeMillis()
    val effectiveMax = if (constraints.carrierCapacity > 0)
      constraints.carrierCapacity
    else
      constraints.maxBatchSize

    val (ready, remaining) = splitReady(now, effectiveMax)
    waitingQueue = remaining
    Future.successful(ready)
  }

  override def pendingCount: Int = waitingQueue.map(_.items.size).sum

  /**
   * 拆分等待队列为"就绪可发"和"继续等待"两部分。
   *
   * 覆盖此方法可实现自定义成批逻辑（如按 recipeId 分组）。
   */
  protected def splitReady(now: Long, effectiveMax: Int): (Seq[SubBatch[Item]], Vector[SubBatch[Item]]) = {
    if (waitingQueue.isEmpty) return (Seq.empty, waitingQueue)

    val totalPending = waitingQueue.map(_.items.size).sum
    val timeSinceLastSubmit = now - lastSubmitTime

    // 如果小于最小批次且还在时间窗口内 → 继续等待
    if (totalPending < constraints.minBatchSize &&
        timeSinceLastSubmit < constraints.batchWindow.toMillis) {
      return (Seq.empty, waitingQueue)
    }

    // 取出 batches 直到达到 effectiveMax
    val result = Vector.newBuilder[SubBatch[Item]]
    val remaining = Vector.newBuilder[SubBatch[Item]]
    var accumulated = 0
    var done = false

    waitingQueue.foreach { batch =>
      if (!done && accumulated + batch.items.size <= effectiveMax) {
        result += batch
        accumulated += batch.items.size
        if (accumulated >= effectiveMax) done = true
      } else if (!done && accumulated < effectiveMax) {
        // 切割 batch
        val take = effectiveMax - accumulated
        val (head, tail) = batch.items.splitAt(take)
        result += batch.copy(items = head)
        if (tail.nonEmpty) remaining += batch.copy(items = tail)
        done = true
      } else {
        remaining += batch
      }
    }

    (result.result(), remaining.result())
  }
}
