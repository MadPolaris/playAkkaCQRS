package net.imadz.fab.orchestration

import net.imadz.fab.model._
import net.imadz.fab.repository.RoutingRepository
import net.imadz.m25.business.ChainDsl.{ChainDefinition, NoopReconfirmHandler}
import net.imadz.m25.component._
import net.imadz.m25.pipeline._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Fab 流程引擎——M3 核心编排器。
 *
 * 职责：
 *   1. 根据产品工艺路线（ProductRouting）动态构建执行计划
 *   2. 为每个工序步骤组装 SubBatchProcessor + ReBatchRouter + AreaScheduler
 *   3. 管理 Lot 生命周期：工序推进 → 拆批处理 → 重入 → 合批入库
 *   4. 处理工艺变更：running Lot 可切换到新版本工艺路线
 *
 * 核心循环（每个工序步骤重复）：
 *   areaScheduler.schedule() → SubBatch → processor.process() → classify →
 *     success → 推进到下一步骤（或合批入库）
 *     suspicious → reconfirm → 分流
 *     failure → router.route() → 目标 AreaScheduler.submit()
 */
class FabFlowEngine(
    /** 每个设备区的调度器 */
    areaSchedulers: Map[String, AreaScheduler[String]],
    /** 每个设备区的处理流水线（可共享 SubBatchPipeline 的其他阶段，仅 classify 不同） */
    areaPipelines: Map[String, SubBatchPipeline[String, Any]],
    /** 全局失败项路由器 */
    router: ReBatchRouter[String],
    /** 合批管理器 */
    mergeManager: BatchMergeManager[String],
    /** 工艺路线存储 */
    routingRepo: RoutingRepository
)(implicit ec: ExecutionContext) {

  /**
   * 启动一个 Lot 的完整加工流程。
   *
   * @param lotContext Lot 上下文（产品 ID、片数、载具等）
   * @return Lot 执行结果
   */
  def startLot(lotContext: LotContext): Future[LotExecutionResult] = {
    val routing = routingRepo.findByProduct(lotContext.productId)

    routing.validate match {
      case Left(err) =>
        Future.successful(LotExecutionResult(lotContext.lotId, success = false, error = Some(err)))
      case Right(validRouting) =>
        // 记录原始批次归属（为合批做准备）
        trackOrigin(lotContext, validRouting)

        // 执行工序序列
        executeSteps(lotContext, validRouting.steps, 0)
    }
  }

  /**
   * 递归执行工序步骤。
   *
   * 每一步：
   *   1. scheduler.schedule() → 生成 SubBatch
   *   2. processor.process(batch) → 三分类
   *   3. 可疑项 → reconfirm
   *   4. 失败项 → router.route() → 目标 AreaScheduler.submit()
   *   5. 成功项 → 继续下一步
   */
  private def executeSteps(
      lot: LotContext,
      steps: List[RoutingStep],
      currentIndex: Int
  ): Future[LotExecutionResult] = {
    if (currentIndex >= steps.size) {
      // 所有工序完成 → 检查合批
      return finalizeLot(lot)
    }

    val step = steps(currentIndex)
    val reentryIdx = EquipmentArea.reentryIndex(step.equipmentArea, lot.completedStepIds)
    val effectiveStep = step.copy(reentryIndex = reentryIdx)

    val areaId = effectiveStep.equipmentArea.areaId
    val pipeline = areaPipelines.getOrElse(areaId,
      throw new IllegalStateException(s"No pipeline configured for area: $areaId"))
    val scheduler = areaSchedulers.getOrElse(areaId,
      throw new IllegalStateException(s"No scheduler configured for area: $areaId"))

    val processor = new SubBatchProcessor[String, Any](pipeline)

    for {
      // 1. 调度成批
      batches <- scheduler.schedule()
      _ = if (batches.isEmpty) {
        // 没有就绪的批次——等待更多 items 或时间窗口触发
        return Future.successful(
          LotExecutionResult(lot.lotId, success = true,
            message = Some(s"Waiting for batch at step ${effectiveStep.stepId}")))
      }

      // 2. 处理每个小批次
      results <- Future.sequence(batches.map(b => processStep(b, processor, lot, effectiveStep)))

      // 3. 合并批次结果
      allSuccesses = results.flatMap(r => r.successes.collect { case s: Success[String] => s })
      allFailures  = results.flatMap(r => r.failures.collect { case f: Failure[String] => f })

      // 4. 成功项继续，失败项路由
      _ <- handleFailures(allFailures, effectiveStep.equipmentArea.areaId)

      // 5. 更新 Lot 上下文，继续下一步
      updatedLot = lot.stepCompleted(effectiveStep.stepId)
      result <- executeSteps(updatedLot, steps, currentIndex + 1)

    } yield result
  }

  private def processStep(
      batch: SubBatch[String],
      processor: SubBatchProcessor[String, Any],
      lot: LotContext,
      step: RoutingStep
  ): Future[SubBatchResult[Classification[String]]] = {

    for {
      rawResult <- processor.process(batch)

      // 可疑项复核
      resolved <- if (rawResult.suspicious.nonEmpty) {
        val suspiciousItems = rawResult.suspicious.collect { case s: Suspicious[String] => s }
        // 使用默认复核处理器——生产环境可注入设备区特定的复核逻辑
        new NoopReconfirmHandler[String].reconfirm(suspiciousItems).map { reconfirmed =>
          val newSuccess = reconfirmed.collect { case s: Success[String] => s }
          val newFailure = reconfirmed.collect { case f: Failure[String] => f }
          SubBatchResult(rawResult.batchId,
            rawResult.successes ++ newSuccess,
            rawResult.failures ++ newFailure,
            Seq.empty)
        }
      } else Future.successful(rawResult)

    } yield resolved
  }

  private def handleFailures(
      failures: Seq[Failure[String]],
      currentAreaId: String
  ): Future[Unit] = {
    if (failures.isEmpty) return Future.successful(())

    router.route(failures, ProcessContext(
      currentAreaId = currentAreaId,
      retryCount = 0 // 首次失败——后续重试由路由决策处理
    )).flatMap { decisions =>
      // 按目标设备区分组
      val grouped: Map[NextStep, Seq[String]] = decisions.groupMap(_.nextStep)(_.item)

      Future.sequence {
        grouped.map { case (nextStep, items) =>
          nextStep match {
            case NextStep.RetrySameArea(_) =>
              areaSchedulers.get(currentAreaId).map(_.submit(items, ItemSource.ReBatch(currentAreaId)))
                .getOrElse(Future.successful(ScheduleResult(0, items.size, 0)))

            case NextStep.RouteToArea(targetAreaId, _) =>
              areaSchedulers.get(targetAreaId).map(_.submit(items, ItemSource.ReBatch(currentAreaId)))
                .getOrElse(Future.successful(ScheduleResult(0, items.size, 0)))

            case NextStep.Scrap =>
              // 报废——从合批追踪中移除
              Future.successful(ScheduleResult(0, items.size, 0))

            case NextStep.ManualIntervention(ticketId) =>
              // 生成工单，暂停处理
              println(s"[MANUAL] Ticket $ticketId created for items: ${items.mkString(", ")}")
              Future.successful(ScheduleResult(0, items.size, 0))
          }
        }
      }.map(_ => ())
    }
  }

  private def trackOrigin(lot: LotContext, routing: ProductRouting): Unit = {
    if (routing.mergeBeforeWarehouse) {
      // 生成逻辑 wafer ID（实际应为真实 wafer ID）
      (0 until lot.originalWaferCount).foreach { idx =>
        mergeManager.trackOrigin(s"${lot.lotId}-WAFER-$idx", lot.lotId)
      }
    }
  }

  private def finalizeLot(lot: LotContext): Future[LotExecutionResult] = {
    if (!lot.requiresMerge) {
      return Future.successful(LotExecutionResult(lot.lotId, success = true,
        message = Some("All steps completed, no merge required")))
    }

    mergeManager.merge(lot.lotId).map { merged =>
      LotExecutionResult(lot.lotId, success = true,
        message = Some(s"Merged: ${merged.mergedItems.size} OK, ${merged.scrappedItems.size} scrapped"))
    }
  }

  /**
   * 处理工艺路线变更——running Lot 切换到新版本工艺路线。
   *
   * 场景：产品工程师发现当前工艺有缺陷，发布了新版本。
   * 已经开始的 Lot 可以继续用旧版本，也可以切换到新版本。
   */
  def reRoute(lot: LotContext, newProductId: String): Future[LotExecutionResult] = {
    val newRouting = routingRepo.findByProduct(newProductId)
    val newLot = lot.copy(productId = newProductId, currentStepIndex = 0)
    startLot(newLot)
  }
}

/**
 * Lot 执行结果
 */
case class LotExecutionResult(
    lotId: String,
    success: Boolean,
    message: Option[String] = None,
    error: Option[String] = None
)
