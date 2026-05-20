package net.imadz.domain.values

/**
 * Lot 上下文——追踪晶圆批次在 Fab 中的加工状态。
 *
 * 每个 Lot（25 片晶圆）在加工过程中可能被拆散——
 * 部分片 rework 去了别的设备区、部分片报废、部分片正常前进。
 * LotContext 记录原始 Lot 归属，供合批入库时使用。
 */
case class LotContext(
    lotId: String,
    productId: String,
    /** 原始批次中的总片数 */
    originalWaferCount: Int = 25,
    /** 当前剩余合格片数 */
    activeWaferCount: Int = 25,
    /** 当前工序步骤索引（在 ProductRouting.steps 中的位置） */
    currentStepIndex: Int = 0,
    /** 已完成的步骤 ID 列表（用于重入计数） */
    completedStepIds: List[String] = Nil,
    /** 报废片数 */
    scrappedCount: Int = 0,
    /** 需要合批入库 */
    requiresMerge: Boolean = true,
    /** 所属 FOUP / Carrier ID */
    carrierId: Option[String] = None
) {

  /** 是否所有工序已完成 */
  def isComplete(totalSteps: Int): Boolean =
    currentStepIndex >= totalSteps

  /** 进度百分比 */
  def progressPercent(totalSteps: Int): Double =
    if (totalSteps == 0) 100.0
    else (currentStepIndex.toDouble / totalSteps) * 100.0

  /** 记录一道工序完成 */
  def stepCompleted(stepId: String): LotContext =
    copy(
      currentStepIndex = currentStepIndex + 1,
      completedStepIds = completedStepIds :+ stepId
    )

  /** 部分片报废 */
  def scrap(count: Int): LotContext =
    copy(
      activeWaferCount = activeWaferCount - count,
      scrappedCount = scrappedCount + count
    )
}

/**
 * 单个 Wafer 上下文——追踪单片晶圆的状态。
 *
 * 当 Lot 拆散时（如部分片去 rework），每片 Wafer 独立追踪。
 */
case class WaferContext(
    waferId: String,
    lotId: String,
    /** 当前所在的设备区 */
    currentAreaId: String,
    /** 单片在当前设备区的重试次数 */
    retryCount: Int = 0,
    /** 如果处于 rework 状态，记录原始工艺步骤 */
    reworkFromStepId: Option[String] = None
)
