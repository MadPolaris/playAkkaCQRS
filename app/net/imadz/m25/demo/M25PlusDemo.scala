package net.imadz.m25.demo

import net.imadz.m25.component._
import net.imadz.m25.pipeline._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
 * M2.5+ 演示：标准组件库组装。
 *
 * 展示如何用同一套组件定义 recharge 和 purchase 两条业务链路——
 * 区别仅在于 ErrorCodeMapping 和 ReBatchPolicy，Pipeline 完全相同。
 *
 * 对比 M2.5 模板方式：
 *   模板：new ExternalTwoPhaseChainTemplate().materialize(Params(...))
 *         → 生成 6 个 EventSourcedBehavior FSM（563 行模板 + 独立协议类型）
 *   组件：defineChain(chainId, errorMapping, pipeline, router, scheduler)
 *         → 组装 4 个标准组件（无需生成代码、统一修复点）
 */
object M25PlusDemo {

  // ============================================================
  // 模拟的 Pipeline 组件（生产环境由 AkkaConnectorFactory 注入）
  // ============================================================

  /** 模拟的 SFTP 连接器引用（生产环境：AkkaSftpConnectorRef） */
  object MockSftpRef extends SftpConnectorRef {
    import net.imadz.infrastructure.connector.SftpConnector._
    override def upload(l: String, r: String): Future[UploadResult] =
      Future.successful(UploadSuccess(r, 4096L))
    override def download(r: String, l: String): Future[DownloadResult] =
      Future.successful(DownloadSuccess(l, 2048L))
    override def listFiles(d: String, p: String): Future[ListResult] =
      Future.successful(ListSuccess(Seq(
        SftpFileInfo("response-batch-001.xml", 2048L, System.currentTimeMillis(), false))))
  }

  /** 模拟的文件生成器 */
  class MockFileGen[Item] extends FileGenerator[Item] {
    override def generate(items: Seq[Item], ctx: Map[String, Any]): Future[GeneratedFile] =
      Future.successful(GeneratedFile("/tmp/batch-001.xml", "batch-001.xml", 4096L, "xml"))
  }

  /** 模拟的上传器 */
  object MockUploader extends FileUploader {
    override def upload(file: GeneratedFile, ctx: Map[String, Any]): Future[UploadReceipt] =
      Future.successful(UploadReceipt("/remote/batch-001.xml", 4096L, System.currentTimeMillis()))
  }

  /** 模拟的 Ack 等待器 */
  object MockAckWaiter extends AckWaiter {
    override def waitForAck(receipt: UploadReceipt, ctx: Map[String, Any]): Future[AckResult] =
      Future.successful(AckReceived)
  }

  /** 模拟的响应轮询器 */
  object MockPoll extends ResponsePoller {
    override def poll(ctx: Map[String, Any]): Future[PollResult] =
      Future.successful(ResponseReady(
        ResponseFile("/tmp/response.xml", "response.xml", 2048L, "<response/>".getBytes)))
  }

  /** 模拟的解析器 */
  object MockParser extends ResponseParser[String] {
    override def parse(file: ResponseFile, ctx: Map[String, Any]): Future[Seq[String]] =
      Future.successful(Seq("OK-充值1001", "TIMEOUT-充值1002", "BALANCE_INSUFFICIENT-充值1003"))
  }

  // ============================================================
  // 标准组件
  // ============================================================

  /** 标准银行 Pipeline——充值/申购共用 */
  def standardBankPipeline: SubBatchPipeline[String, String] =
    SubBatchPipeline[String, String](
      fileGen  = new MockFileGen[String],
      upload   = MockUploader,
      waitAck  = MockAckWaiter,
      pollResp = MockPoll,
      parse    = MockParser,
      classify = new ErrorCodeBasedClassifier[String, String] {
        override def errorCodeMapping: ErrorCodeMapping = ErrorCodeMapping(
          successCodes     = Set("OK"),
          failureCodes     = Map("BALANCE_INSUFFICIENT" -> NextStep.Scrap),
          suspiciousCodes  = Set("TIMEOUT", "NETWORK_ERROR")
        )
        override def extractCode(raw: String): String =
          raw.split("-").headOption.getOrElse("UNKNOWN")
        override def associateItem(raw: String, items: Seq[String]): Option[String] =
          items.find(i => raw.contains(i))
      }
    )

  /** Recharge 专用分类器——仅 ErrorCodeMapping 不同 */
  def rechargeClassifier: ErrorCodeBasedClassifier[String, String] =
    new ErrorCodeBasedClassifier[String, String] {
      override def errorCodeMapping: ErrorCodeMapping = ErrorCodeMapping(
        successCodes     = Set("OK"),
        failureCodes     = Map("BALANCE_INSUFFICIENT" -> NextStep.Scrap),
        suspiciousCodes  = Set("TIMEOUT")
      )
      override def extractCode(raw: String): String =
        raw.split("-").headOption.getOrElse("UNKNOWN")
      override def associateItem(raw: String, items: Seq[String]): Option[String] =
        items.find(i => raw.contains(i))
    }

  /** Purchase 专用分类器——仅 ErrorCodeMapping 不同 */
  def purchaseClassifier: ErrorCodeBasedClassifier[String, String] =
    new ErrorCodeBasedClassifier[String, String] {
      override def errorCodeMapping: ErrorCodeMapping = ErrorCodeMapping(
        successCodes     = Set("OK"),
        failureCodes     = Map("QUOTA_EXCEEDED" -> NextStep.Scrap),
        suspiciousCodes  = Set("TIMEOUT", "PARTIAL")
      )
      override def extractCode(raw: String): String =
        raw.split("-").headOption.getOrElse("UNKNOWN")
      override def associateItem(raw: String, items: Seq[String]): Option[String] =
        items.find(i => raw.contains(i))
    }

  // ============================================================
  // 组装：业务链路 = 标准组件 + 业务参数
  // ============================================================

  /** 通用链路组装器 */
  case class ChainAssembly[Item](
      chainId: String,
      processor: SubBatchProcessor[Item, String],
      reconfirm: ReconfirmHandler[Item],
      router: ReBatchRouter[Item],
      scheduler: AreaScheduler[Item]
  ) {
    def processBatch(batch: SubBatch[Item])(implicit ec: ExecutionContext): Future[Unit] = {
      for {
        result <- processor.process(batch)

        // 可疑项 → 复核
        resolved <- if (result.suspicious.nonEmpty) {
          val suspiciousItems = result.suspicious.collect { case s: Suspicious[Item] => s }
          reconfirm.reconfirm(suspiciousItems).map { resolved =>
            val newSuccesses = resolved.collect { case s: Success[Item] => s }
            val newFailures  = resolved.collect { case f: Failure[Item] => f }
            // 合并结果
            SubBatchResult(result.batchId,
              result.successes ++ newSuccesses,
              result.failures ++ newFailures,
              Seq.empty)
          }
        } else Future.successful(result)

        // 失败项 → 智能路由
        _ <- if (resolved.failures.nonEmpty) {
          val failedItems = resolved.failures.collect { case f: Failure[Item] => f }
          router.route(failedItems, ProcessContext(
            currentAreaId = chainId, retryCount = 0,
            originalBatchId = Some(batch.batchId)
          )).flatMap { decisions =>
            // 按目标区域分组，提交到对应调度器
            val retryItems = decisions.collect {
              case RoutingDecision(item, NextStep.RetrySameArea(_), _) => item
            }
            if (retryItems.nonEmpty)
              scheduler.submit(retryItems, ItemSource.ReBatch(chainId))
            else
              Future.successful(())
          }
        } else Future.successful(())

        // 成功项 → 继续后续（通知等——此处简化）
        _ = println(s"[${chainId}] Batch ${batch.batchId}: ${resolved.successes.size} OK, ${resolved.failures.size} FAIL")
      } yield ()
    }
  }

  /** 充值链路定义 */
  def defineRechargeChain(implicit ec: ExecutionContext): ChainAssembly[String] = {
    val pipeline = standardBankPipeline.copy(classify = rechargeClassifier)

    ChainAssembly(
      chainId   = "recharge",
      processor = new SubBatchProcessor[String, String](pipeline),
      reconfirm = new ReconfirmHandler[String] {
        override def reconfirm(
            suspicious: Seq[Suspicious[String]]): Future[Seq[Classification[String]]] =
          Future.successful(suspicious.map(s =>
            Failure(s.item, FailureReason(s.reason.code, "Reconfirm failed"))))
      },
      router    = new PolicyBasedReBatchRouter[String](ReBatchPolicy.salarySavingDefault),
      scheduler = new WindowedAreaScheduler[String](
        PhysicalConstraints(1, 100, 0, 10.minutes, allowMixedSources = true)
      ) {
        override def generateBatchId(): String = s"recharge-${System.currentTimeMillis()}"
      }
    )
  }

  /** 申购链路定义 */
  def definePurchaseChain(implicit ec: ExecutionContext): ChainAssembly[String] = {
    val pipeline = standardBankPipeline.copy(classify = purchaseClassifier)

    ChainAssembly(
      chainId   = "purchase",
      processor = new SubBatchProcessor[String, String](pipeline),
      reconfirm = new ReconfirmHandler[String] {
        override def reconfirm(
            suspicious: Seq[Suspicious[String]]): Future[Seq[Classification[String]]] =
          Future.successful(suspicious.map(s =>
            Failure(s.item, FailureReason(s.reason.code, "Reconfirm failed"))))
      },
      router    = new PolicyBasedReBatchRouter[String](ReBatchPolicy.salarySavingDefault),
      scheduler = new WindowedAreaScheduler[String](
        PhysicalConstraints(1, 100, 0, 10.minutes, allowMixedSources = true)
      ) {
        override def generateBatchId(): String = s"purchase-${System.currentTimeMillis()}"
      }
    )
  }

  // ============================================================
  // 关键对比：M2.5 模板 vs M2.5+ 组件库
  // ============================================================

  def main(args: Array[String]): Unit = {
    println("=" * 60)
    println("  M2.5+ 标准组件库 — Demonstration")
    println("=" * 60)
    println()
    println("  Phase 1 已完成组件：")
    println("    connector/  — ConnectorRef, HttpConnector, SftpConnector, SmsConnector, EncodingStrategy")
    println("    component/  — SubBatchProcessor, SubBatchPipeline, ResultClassifier")
    println("                  ReconfirmHandler, ReBatchRouter, AreaScheduler")
    println("    pipeline/   — FileGenStage, SftpUploadStage, ResponsePollStage, ResponseParseStage")
    println()
    println("  Phase 2 (待实现)：")
    println("    business/   — ChainDsl (声明式 DSL), ChainTemplates")
    println()
    println("  Phase 3 (探索)：")
    println("    fab/        — EquipmentArea, ProductRouting, FabFlowEngine, BatchMergeManager")
    println()
    println("  关键改进 vs M2.5 模板：")
    println("    - 修复 retry 逻辑：改 SubBatchProcessor 一处 → 所有链自动生效")
    println("    - 新增连接器协议：实现 FileUploader 接口 → 插入 Pipeline")
    println("    - 修改分类规则：改 ErrorCodeMapping 配置 → 不碰代码")
    println("    - ReBatch 决策：改 ReBatchPolicy.actionMap → 不修改状态机")
    println("    - 新增业务链路：复制 ~20 行组装配置 → 不生成新 FSM")
  }
}
