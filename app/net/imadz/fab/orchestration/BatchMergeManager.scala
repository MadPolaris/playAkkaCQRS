package net.imadz.fab.orchestration

import scala.concurrent.{ExecutionContext, Future}

/**
 * 合批管理器——Fab M3 特有组件。
 *
 * 背景：原始 Lot（25 片）在加工过程中可能被拆散——
 *   - 部分片 rework 去了别的设备区（物理上离开原 FOUP）
 *   - 部分片报废（不再参与合批）
 *   - 部分片正常前进（在不同设备区并行处理）
 *
 * 入库时，需要把同一原始 Lot 的所有合格片重新合并，
 * 确保整批晶圆一起入库。
 */

/** 合批决策 */
sealed trait MergeDecision
object MergeDecision {
  /** 还有片未完成，继续等待 */
  case object WaitForOthers extends MergeDecision
  /** 所有片已完成，可以合批 */
  case class ReadyToMerge(completedItems: Seq[String], totalCount: Int) extends MergeDecision
  /** 部分片已报废，剩余合格片可以合批 */
  case class PartialMerge(completedItems: Seq[String], scrappedItems: Seq[String]) extends MergeDecision
}

case class MergedBatch(
    originalLotId: String,
    mergedItems: Seq[String],
    scrappedItems: Seq[String],
    mergeTimestamp: Long
)

/**
 * 合批管理器接口。
 *
 * trackOrigin + onItemComplete + merge 三个方法协作：
 *   - 每个 item 开始时调用 trackOrigin 记录归属
 *   - 每个 item 完成时调用 onItemComplete 检查状态
 *   - 所有 item 完成时调用 merge 触发合批
 */
trait BatchMergeManager[Item] {

  /** 记录 item 的原始批次归属 */
  def trackOrigin(item: Item, originalLotId: String): Future[Unit]

  /** 当 item 完成所有工序后，检查原始 Lot 是否全部完成 */
  def onItemComplete(item: Item, originalLotId: String): Future[MergeDecision]

  /** 触发合批入库 */
  def merge(originalLotId: String): Future[MergedBatch]

  /** 查询原始 Lot 的完成进度 */
  def progress(originalLotId: String): Future[MergeProgress]
}

case class MergeProgress(
    originalLotId: String,
    totalItems: Int,
    completedItems: Int,
    inProgressItems: Int,
    scrappedItems: Int
)

/**
 * 基于内存追踪的合批管理器实现。
 *
 * 生产环境中应替换为持久化存储（如 Akka Persistence 或数据库）。
 */
class InMemoryBatchMergeManager[Item](
    itemKey: Item => String // 从 item 提取唯一标识
)(implicit ec: ExecutionContext) extends BatchMergeManager[Item] {

  // lotId → (total: Set[String], completed: Set[String], scrapped: Set[String])
  private val tracker = scala.collection.mutable.Map.empty[
    String, (scala.collection.mutable.Set[String],
             scala.collection.mutable.Set[String],
             scala.collection.mutable.Set[String])
  ]

  override def trackOrigin(item: Item, originalLotId: String): Future[Unit] = {
    val key = itemKey(item)
    tracker.synchronized {
      val (all, completed, scrapped) = tracker.getOrElseUpdate(
        originalLotId,
        (scala.collection.mutable.Set.empty, scala.collection.mutable.Set.empty, scala.collection.mutable.Set.empty)
      )
      all += key
    }
    Future.successful(())
  }

  override def onItemComplete(item: Item, originalLotId: String): Future[MergeDecision] = {
    val key = itemKey(item)
    tracker.synchronized {
      tracker.get(originalLotId).map { case (all, completed, scrapped) =>
        completed += key
        val remaining = all -- completed -- scrapped

        if (remaining.isEmpty) {
          if (scrapped.isEmpty) MergeDecision.ReadyToMerge(completed.toSeq, all.size)
          else MergeDecision.PartialMerge(completed.toSeq, scrapped.toSeq)
        } else {
          MergeDecision.WaitForOthers
        }
      }.getOrElse(MergeDecision.WaitForOthers)
    } match {
      case decision => Future.successful(decision)
    }
  }

  override def merge(originalLotId: String): Future[MergedBatch] = {
    tracker.synchronized {
      tracker.get(originalLotId).map { case (_, completed, scrapped) =>
        MergedBatch(originalLotId, completed.toSeq, scrapped.toSeq, System.currentTimeMillis())
      }.getOrElse(MergedBatch(originalLotId, Seq.empty, Seq.empty, System.currentTimeMillis()))
    } match {
      case result =>
        // Clean up tracker after merge
        tracker.synchronized { tracker.remove(originalLotId) }
        Future.successful(result)
    }
  }

  override def progress(originalLotId: String): Future[MergeProgress] = {
    tracker.synchronized {
      tracker.get(originalLotId).map { case (all, completed, scrapped) =>
        MergeProgress(originalLotId, all.size, completed.size,
          all.size - completed.size - scrapped.size, scrapped.size)
      }.getOrElse(MergeProgress(originalLotId, 0, 0, 0, 0))
    } match {
      case p => Future.successful(p)
    }
  }
}
