package net.imadz.fab.model

import scala.concurrent.duration.FiniteDuration

/**
 * 产品工艺路线——决定晶圆在 Fab 中的加工序列。
 *
 * 这是 M3 区别于 M2.5 的核心：
 *   - M2.5: 工序序列是静态代码（recharge: request→response→reconfirm→success|failure）
 *   - M3:    工序序列是数据（ProductRouting.steps），从数据库加载，运行时决定
 *
 * 同 M2.5 一样使用 SubBatchProcessor / ResultClassifier / ReBatchRouter / AreaScheduler 组件。
 */
case class RoutingStep(
    stepId: String,
    equipmentArea: EquipmentArea,
    /** 工艺配方 ID */
    recipeId: String,
    /** 预计时长（用于 SLA 监控） */
    expectedDuration: FiniteDuration,
    /** 最大重试次数 */
    maxRetries: Int = 3,
    /** 备选设备区（主设备不可用时切换） */
    fallbackAreas: List[EquipmentArea] = Nil,
    /**
     * 重入标记——同一 Lot 第几次访问此设备区。
     * 由 FabFlowEngine 在构建执行计划时计算，不需要手动设置。
     */
    reentryIndex: Int = 0
)

/**
 * 产品工艺路线——一个产品在 Fab 中的完整加工序列。
 *
 * 版本号用于支持工艺变更：running Lot 可使用旧版本继续或切换到新版本。
 */
case class ProductRouting(
    productId: String,
    steps: List[RoutingStep],
    version: Int = 1,
    /** 入库前是否需要合批（原始批次追踪） */
    mergeBeforeWarehouse: Boolean = true
) {
  /** 验证路线完整性 */
  def validate: Either[String, ProductRouting] = {
    if (steps.isEmpty) Left(s"Product $productId has no routing steps")
    else if (steps.exists(_.expectedDuration.toMillis <= 0)) Left(s"Product $productId has invalid duration")
    else Right(this)
  }

  /** 该产品访问各设备区的次数统计 */
  def areaVisitCounts: Map[String, Int] =
    steps.groupBy(_.equipmentArea.areaId).view.mapValues(_.size).toMap

  /** 是否重入过指定设备区 */
  def hasReentry(areaId: String): Boolean =
    areaVisitCounts.getOrElse(areaId, 0) > 1
}

object ProductRouting {

  /**
   * 一个典型的逻辑产品工艺路线示例（10 道工序，含重入 Lithography）：
   *
   *   CLEAN → DIFF → LITHO → ETCH → LITHO(重入) → IMPL → DEP → CMP → MET → DRY → LOG
   */
  val exampleRouting: ProductRouting = {
    import scala.concurrent.duration._
    import EquipmentArea._

    ProductRouting(
      productId = "LOGIC-28NM-A",
      steps = List(
        RoutingStep("op-010", WetClean,    "CLEAN-PRE-001",  30.minutes),
        RoutingStep("op-020", Diffusion,   "DIFF-OX-001",    60.minutes, fallbackAreas = List(Diffusion)),
        RoutingStep("op-030", Lithography, "LITHO-28-001",   45.minutes, fallbackAreas = List(Lithography)),
        RoutingStep("op-040", Etch,        "ETCH-POLY-001",  40.minutes),
        RoutingStep("op-050", Lithography, "LITHO-28-002",   50.minutes, fallbackAreas = List(Lithography)), // 重入
        RoutingStep("op-060", Implant,     "IMPL-SD-001",    35.minutes),
        RoutingStep("op-070", Deposition,  "DEP-CVD-001",    55.minutes),
        RoutingStep("op-080", CMP,         "CMP-PLANAR-001", 30.minutes),
        RoutingStep("op-090", Metrology,   "MET-INLINE-001", 20.minutes, maxRetries = 2),
        RoutingStep("op-100", Drying,      "DRY-FINAL-001",  15.minutes),
        RoutingStep("op-110", Logistics,   "LOG-WIP-001",    10.minutes)
      ),
      version = 1,
      mergeBeforeWarehouse = true
    )
  }
}
