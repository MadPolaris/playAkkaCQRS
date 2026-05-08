package net.imadz.fab.demo

import net.imadz.fab.model._
import net.imadz.fab.orchestration._
import net.imadz.fab.repository.{DemoRoutingRepo, InMemoryRoutingRepository}
import net.imadz.m25.business.ChainTemplates
import net.imadz.m25.component._
import net.imadz.m25.pipeline._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Fab M3 演示——动态工序编排 + 合批入库。
 *
 * 展示：
 *   1. 产品工艺路线（ProductRouting）驱动 10 个设备区的加工序列
 *   2. 同一设备区多次重入（Lithography 重入、Deposition/Etch 循环）
 *   3. 失败项智能路由 + 重新成批
 *   4. 合批入库——原始 Lot 拆散后重新合并
 *
 * 与 M2.5 模板方式的对比：
 *   M2.5:  static chain: recharge-req → recharge-resp → ... (6 FSM, 固定拓扑)
 *   M3:    dynamic routing: ProductRouting.steps (N steps, 数据驱动)
 *          + BatchMergeManager (合批)
 */
object FabDemo {

  // ============================================================
  // M2.5+ 组件直接复用
  // ============================================================

  /** 为每个设备区创建模拟 Pipeline */
  def createAreaPipeline(areaId: String): SubBatchPipeline[String, String] = {
    // 生产环境：每个设备区有真实的 Connector（HTTP to equipment API）
    // 演示：使用 Mock 组件
    SubBatchPipeline[String, String](
      fileGen  = new MockFileGen,
      upload   = MockUploader,
      waitAck  = MockAckWaiter,
      pollResp = MockPoller,
      parse    = MockParser,
      classify = new net.imadz.m25.component.ErrorCodeBasedClassifier[String, String] {
        override def errorCodeMapping: ErrorCodeMapping = ErrorCodeMapping.empty
        override def extractCode(raw: String): String = raw.split("-").headOption.getOrElse("UNKNOWN")
        override def associateItem(raw: String, items: Seq[String]): Option[String] = items.headOption
      }.asInstanceOf[ResultClassifier[String, String]]
    )
  }

  // ============================================================
  // 设备区特定配置
  // ============================================================

  /** 设备区 → PhysicalConstraints 映射——Fab 物理约束 */
  def areaConstraints: Map[String, PhysicalConstraints] = Map(
    "CLEAN"  -> PhysicalConstraints(1, 25, 25, 15.minutes, allowMixedSources = false),
    "DIFF"   -> PhysicalConstraints(1, 25, 25, 20.minutes, allowMixedSources = false),
    "LITHO"  -> PhysicalConstraints(1, 25, 25, 10.minutes, allowMixedSources = false), // 光刻不能混批
    "ETCH"   -> PhysicalConstraints(1, 25, 25, 15.minutes, allowMixedSources = false),
    "IMPL"   -> PhysicalConstraints(1, 25, 25, 20.minutes, allowMixedSources = false),
    "DEP"    -> PhysicalConstraints(1, 25, 25, 20.minutes, allowMixedSources = false),
    "CMP"    -> PhysicalConstraints(1, 25, 25, 15.minutes, allowMixedSources = false),
    "MET"    -> PhysicalConstraints(1, 25, 25, 10.minutes, allowMixedSources = true),  // 量测可以混批
    "DRY"    -> PhysicalConstraints(1, 25, 25, 10.minutes, allowMixedSources = true),
    "LOG"    -> PhysicalConstraints(1, 25, 25, 5.minutes,  allowMixedSources = true)   // 物流可以混批
  )

  /** 设备区 → ReBatchPolicy 映射——不同设备区不同失败处理策略 */
  def areaRouterPolicies: Map[String, ReBatchPolicy] = Map(
    "LITHO" -> ReBatchPolicy(
      maxRetries = 3,
      actionMap = Map(
        "ALIGNMENT_ERROR" -> NextStep.RetrySameArea(1.minute),
        "RESIST_FAILURE"  -> NextStep.RouteToArea("CLEAN", Some("CLEAN-REWORK-001")),
        "HARDWARE_FAULT"  -> NextStep.RouteToArea("LITHO", Some("LITHO-FALLBACK-001"))
      ),
      defaultCooldown = 5.minutes
    ),
    "ETCH" -> ReBatchPolicy(
      maxRetries = 2,
      actionMap = Map(
        "OVER_ETCH"       -> NextStep.Scrap,
        "UNDER_ETCH"      -> NextStep.RetrySameArea(30.seconds),
        "UNIFORMITY_ERR"  -> NextStep.RouteToArea("ETCH", Some("ETCH-REWORK-001"))
      ),
      defaultCooldown = 3.minutes
    ),
    "MET" -> ReBatchPolicy(
      maxRetries = 2,
      actionMap = Map(
        "OUT_OF_SPEC"     -> NextStep.RouteToArea("CLEAN", Some("CLEAN-REWORK-001")),
        "MEASUREMENT_ERR" -> NextStep.RetrySameArea(15.seconds)
      ),
      defaultCooldown = 2.minutes
    )
  )

  // ============================================================
  // 组装 FabFlowEngine
  // ============================================================

  def createEngine(implicit ec: ExecutionContext): FabFlowEngine = {
    val repo = DemoRoutingRepo.create()

    // 为每个设备区创建 Pipeline + Scheduler + Router
    val pipelines: Map[String, SubBatchPipeline[String, Any]] =
      EquipmentArea.all.map { area =>
        area.areaId -> createAreaPipeline(area.areaId).asInstanceOf[SubBatchPipeline[String, Any]]
      }.toMap

    val schedulers: Map[String, AreaScheduler[String]] =
      EquipmentArea.all.map { area =>
        val constraints = areaConstraints.getOrElse(area.areaId, PhysicalConstraints())
        area.areaId -> new WindowedAreaScheduler[String](constraints) {
          override def generateBatchId(): String = s"${area.areaId}-${System.currentTimeMillis()}"
        }
      }.toMap

    // 全局路由器——fallback 到默认策略
    val defaultRouterPolicy = ReBatchPolicy.salarySavingDefault
    val globalRouter = new PolicyBasedReBatchRouter[String](defaultRouterPolicy)

    val mergeMgr = new InMemoryBatchMergeManager[String](identity)

    new FabFlowEngine(
      areaSchedulers = schedulers,
      areaPipelines  = pipelines,
      router         = globalRouter,
      mergeManager   = mergeMgr,
      routingRepo    = repo
    )
  }

  // ============================================================
  // 演示运行
  // ============================================================

  def main(args: Array[String]): Unit = {
    println("=" * 60)
    println("  Fab M3 — Dynamic Process Sequencing Demo")
    println("=" * 60)
    println()

    import scala.concurrent.ExecutionContext.Implicits.global

    val engine = createEngine

    // 演示产品工艺路线
    val routing = ProductRouting.exampleRouting
    println(s"  Product: ${routing.productId}")
    println(s"  Steps: ${routing.steps.size}")
    println(s"  Merge before warehouse: ${routing.mergeBeforeWarehouse}")
    println()
    println("  Process Route:")
    routing.steps.zipWithIndex.foreach { case (step, idx) =>
      val reentry = if (step.reentryIndex > 0) s" [REENTRY #${step.reentryIndex}]" else ""
      println(f"    ${idx + 1}%3d. ${step.stepId}%-8s → ${step.equipmentArea.displayName}%-12s (${step.expectedDuration.toMinutes}min)$reentry")
    }
    println()
    println("  Area visit counts:")
    routing.areaVisitCounts.toSeq.sortBy(-_._2).foreach { case (areaId, count) =>
      val name = EquipmentArea.byId(areaId).map(_.displayName).getOrElse(areaId)
      println(f"    $name%-12s: $count times")
    }
    println()

    // 演示 NAND 工艺路线的重入
    val nandRouting = DemoRoutingRepo.create().findByProduct("NAND-96L-A")
    println(s"  NAND Product: ${nandRouting.productId}")
    println(s"  Steps: ${nandRouting.steps.size}")
    println(s"  Deposition reentry: ${nandRouting.areaVisitCounts.getOrElse("DEP", 0)} times")
    println(s"  Etch reentry: ${nandRouting.areaVisitCounts.getOrElse("ETCH", 0)} times")
    println()

    // 演示合批
    println("  BatchMergeManager demonstration:")
    val mergeMgr = new InMemoryBatchMergeManager[String](identity)
    val lotId = "LOT-001"
    (0 until 25).foreach { idx =>
      mergeMgr.trackOrigin(s"$lotId-WAFER-$idx", lotId)
    }
    println(s"    Registered 25 wafers in $lotId")
    println()

    println("  Key takeaway:")
    println("    M2.5+: Same components, different ErrorCodeMapping → recharge/purchase")
    println("    M3:    Same components, dynamic routing → any product, any equipment area")
    println("           + BatchMergeManager for wafer lot merge before warehousing")
    println("           + ReBatchRouter for intelligent rework/scrap decisions")
    println()
    println("  Components reused from M2.5+ with ZERO changes:")
    println("    - SubBatchProcessor  (req→resp→parse→classify)")
    println("    - ResultClassifier   (成功/失败/可疑)")
    println("    - ReconfirmHandler   (可疑复核)")
    println("    - ReBatchRouter      (智能路由)")
    println("    - AreaScheduler      (重新成批)")
    println("    - Connector           (HTTP/SFTP + CB)")
    println("    - EncodingStrategy    (XML/JSON/加密)")
  }

  // ============================================================
  // Mock 组件（生产环境替换为真实 Connector）
  // ============================================================

  class MockFileGen extends FileGenerator[String] {
    override def generate(items: Seq[String], ctx: Map[String, Any]): Future[GeneratedFile] =
      Future.successful(GeneratedFile("/tmp/fab-batch.dat", "fab-batch.dat", 4096L, "binary"))
  }
  object MockUploader extends FileUploader {
    override def upload(file: GeneratedFile, ctx: Map[String, Any]): Future[UploadReceipt] =
      Future.successful(UploadReceipt("/equipment/recipe.dat", 4096L, System.currentTimeMillis()))
  }
  object MockAckWaiter extends AckWaiter {
    override def waitForAck(receipt: UploadReceipt, ctx: Map[String, Any]): Future[AckResult] =
      Future.successful(AckReceived)
  }
  object MockPoller extends ResponsePoller {
    override def poll(ctx: Map[String, Any]): Future[PollResult] =
      Future.successful(ResponseReady(
        ResponseFile("/tmp/result.json", "result.json", 2048L, """{"status":"OK"}""".getBytes)))
  }
  object MockParser extends ResponseParser[String] {
    override def parse(file: ResponseFile, ctx: Map[String, Any]): Future[Seq[String]] =
      Future.successful(Seq("OK-WAFER-001"))
  }
}
