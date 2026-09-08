package net.imadz.domain.values

import net.imadz.fab.model.EquipmentArea
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
case class PorStep(
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
case class Por(
                productId: String,
                steps: List[PorStep],
                version: Int = 1,
                /** 入库前是否需要合批（原始批次追踪） */
                mergeBeforeWarehouse: Boolean = true
) {
  /** 验证路线完整性 */
  def validate: Either[String, Por] = {
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

object Por {

  /**
   * 一个典型的逻辑产品工艺路线示例（10 道工序，含重入 Lithography）：
   *
   *   CLEAN → DIFF → LITHO → ETCH → LITHO(重入) → IMPL → DEP → CMP → MET → DRY → LOG
   */
  val logic28nmPor: Por = {
    import scala.concurrent.duration._
    import EquipmentArea._

    Por(
      productId = "LOGIC-28NM-A",
      steps = List(
        PorStep("op-010", WetClean,    "CLEAN-PRE-001",  30.minutes),
        PorStep("op-020", Diffusion,   "DIFF-OX-001",    60.minutes, fallbackAreas = List(Diffusion)),
        PorStep("op-030", Lithography, "LITHO-28-001",   45.minutes, fallbackAreas = List(Lithography)),
        PorStep("op-040", Etch,        "ETCH-POLY-001",  40.minutes),
        PorStep("op-050", Lithography, "LITHO-28-002",   50.minutes, fallbackAreas = List(Lithography)), // 重入
        PorStep("op-060", Implant,     "IMPL-SD-001",    35.minutes),
        PorStep("op-070", Deposition,  "DEP-CVD-001",    55.minutes),
        PorStep("op-080", CMP,         "CMP-PLANAR-001", 30.minutes),
        PorStep("op-090", Metrology,   "MET-INLINE-001", 20.minutes, maxRetries = 2),
        PorStep("op-100", Drying,      "DRY-FINAL-001",  15.minutes),
        PorStep("op-110", Logistics,   "LOG-WIP-001",    10.minutes)
      ),
      version = 1,
      mergeBeforeWarehouse = true
    )
  }

  /**
   * SK hynix HBM3E 工艺路线 v2（依据 Redknot 视频解析，12-Hi 堆叠 DRAM die 教学简化版）：
   *
   * FEOL 阵列区:  CLEAN(进站清洗) → DIFF(阱区/热氧化) → LITHO(AA 有源区) → ETCH(AA)
   *               → IMPL(沟道注入) → DEP(栅氧/字线沉积) → LITHO(WL 字线, 重入) → ETCH(WL)
   *               → MET(栅 CD 量测 — 关键尺寸)
   * TSV 中段区:   DEP(TSV 绝缘层 CVD) → LITHO(TSV 信号孔, 重入) → ETCH(DRIE 深硅刻蚀)
   *               → ETCH(散热专用通道刻蚀 ★ 不传数据专职导热)
   *               → DEP(TSV 铜填孔) → CMP(TSV reveal) → MET(TSV 量测)
   * 微凸点+减薄:  DEP(微凸点 UBM 金属化 ★) → DRY(晶圆减薄)
   * MR-MUF 整体注塑 ★★（海力士独门，对比对手的逐层 C4/薄膜层压）:
   *               CLEAN(键合前清洗) → DEP(整体入模·注入掺导热颗粒的液态环氧)
   *               → DIFF(一次性加热加压固化·微凸点同步焊接) → MET(X-Ray/声扫空洞检查)
   *
   * 12 片晶圆 = 12 层堆叠的 DRAM die（DRAM-L01..L12）；量测 FAIL 的层拆批返工，
   * 不可返工的报废，其余合批。封批后 die 送去硅中介层（Silicon Interposer）与 GPU 合封。
   */
  val hynixHbm3ePor: Por = {
    import scala.concurrent.duration._
    import EquipmentArea._

    Por(
      productId = "hynix-hbm3e",
      steps = List(
        PorStep("hb-010", WetClean,    "CLEAN-IN-001",      20.minutes),
        PorStep("hb-020", Diffusion,   "DIFF-OX-001",       40.minutes),
        PorStep("hb-030", Lithography, "LITHO-AA-001",      40.minutes, fallbackAreas = List(Lithography)),
        PorStep("hb-040", Etch,        "ETCH-AA-001",       35.minutes),
        PorStep("hb-050", Implant,     "IMPL-CH-001",       25.minutes),
        PorStep("hb-060", Deposition,  "DEP-GOX-WL-001",    45.minutes),
        PorStep("hb-070", Lithography, "LITHO-WL-001",      40.minutes, fallbackAreas = List(Lithography)),
        PorStep("hb-080", Etch,        "ETCH-WL-001",       30.minutes),
        PorStep("hb-090", Metrology,   "MET-GATE-CD-001",   18.minutes, maxRetries = 2),
        PorStep("hb-100", Deposition,  "DEP-TSV-INS-001",   45.minutes),
        PorStep("hb-110", Lithography, "LITHO-TSV-001",     45.minutes, fallbackAreas = List(Lithography)),
        PorStep("hb-120", Etch,        "ETCH-TSV-DRIE-001", 55.minutes),
        PorStep("hb-125", Etch,        "ETCH-TSV-THRM-001", 40.minutes),
        PorStep("hb-130", Deposition,  "DEP-TSV-CU-001",    50.minutes),
        PorStep("hb-140", CMP,         "CMP-TSV-REVEAL-001", 35.minutes),
        PorStep("hb-150", Metrology,   "MET-TSV-001",       18.minutes, maxRetries = 2),
        PorStep("hb-160", Deposition,  "DEP-UBM-BUMP-001",  40.minutes),
        PorStep("hb-170", Drying,      "DRY-THIN-001",      15.minutes),
        PorStep("hb-180", WetClean,    "CLEAN-MRMUF-001",   20.minutes),
        PorStep("hb-190", Deposition,  "DEP-MRMUF-MOLD-001", 55.minutes),
        PorStep("hb-200", Diffusion,   "DIFF-MRMUF-CURE-001", 45.minutes),
        PorStep("hb-210", Metrology,   "MET-MRMUF-VOID-001", 18.minutes, maxRetries = 2)
      ),
      version = 2,
      mergeBeforeWarehouse = true
    )
  }
}
