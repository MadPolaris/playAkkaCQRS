package net.imadz.m25.business

import net.imadz.m25.component._
import net.imadz.m25.pipeline._
import net.imadz.monarch.LifecycleHooks

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

/**
 * M2.5+ 声明式链路定义 DSL。
 *
 * 目标：让业务分析师也能看懂链路配置，而不需要了解 FSM / EventSourcedBehavior / EntityTypeKey。
 *
 * 使用示例：
 * {{{
 *   val recharge = ChainDsl.define("recharge") {
 *     fileGen   { _.toXml("recharge-request.xml") }
 *     upload    { via.sftp.timeout(60.seconds) }
 *     pollResp  { every(30.seconds).maxAttempts(20) }
 *     parse     { asXml }
 *     classify  {
 *       success   when { _.code == "OK" }
 *       failure   when { _.code == "BALANCE_INSUFFICIENT" } action Scrap
 *       suspicious when { _.code == "TIMEOUT" }
 *     }
 *     reconfirm { verify via.coreApi(timeout = 30.seconds) }
 *     onFailure {
 *       when("BALANCE_INSUFFICIENT") { scrap }
 *       when("TIMEOUT")             { retry(delay = 5.minutes) }
 *       otherwise                   { manual }
 *     }
 *     scheduling {
 *       minBatchSize = 1
 *       maxBatchSize = 100
 *       batchWindow  = 10.minutes
 *     }
 *   }
 * }}}
 */
object ChainDsl {

  // ---- Entry point ----

  def define[Item](chainId: String)(build: ChainBuilder[Item] => Unit): ChainDefinition[Item] = {
    val builder = new ChainBuilder[Item](chainId)
    build(builder)
    builder.build()
  }

  // ---- Builder ----

  class ChainBuilder[Item](val chainId: String) {
    private var _fileGen: Option[FileGenerator[Item]] = None
    private var _upload: Option[FileUploader] = None
    private var _waitAck: Option[AckWaiter] = None
    private var _pollResp: Option[ResponsePoller] = None
    private var _parse: Option[ResponseParser[_]] = None
    private var _classify: Option[ResultClassifier[_, Item]] = None
    private var _reconfirm: Option[ReconfirmHandler[Item]] = None
    private var _routerPolicy: ReBatchPolicy = ReBatchPolicy.salarySavingDefault
    private var _constraints: PhysicalConstraints = PhysicalConstraints()

    // ---- Processing stages ----

    def fileGen(fg: FileGenerator[Item]): Unit = { _fileGen = Some(fg) }
    def upload(up: FileUploader): Unit = { _upload = Some(up) }
    def waitAck(wa: AckWaiter): Unit = { _waitAck = Some(wa) }
    def pollResp(pr: ResponsePoller): Unit = { _pollResp = Some(pr) }
    def parse[Raw](rp: ResponseParser[Raw]): Unit = { _parse = Some(rp) }
    def classify[Raw](rc: ResultClassifier[Raw, Item]): Unit = { _classify = Some(rc) }

    // ---- Reconfirm ----

    def reconfirm(rh: ReconfirmHandler[Item]): Unit = { _reconfirm = Some(rh) }

    // ---- Failure routing ----

    def onFailure(policy: ReBatchPolicy): Unit = { _routerPolicy = policy }

    /** 声明式路由 DSL */
    def onFailure(build: FailureRouterBuilder => Unit): Unit = {
      val frb = new FailureRouterBuilder
      build(frb)
      _routerPolicy = frb.build()
    }

    // ---- Scheduling ----

    def scheduling(build: SchedulingBuilder => Unit): Unit = {
      val sb = new SchedulingBuilder
      build(sb)
      _constraints = sb.build()
    }

    // ---- Build ----

    def build(): ChainDefinition[Item] = {
      val pipeline = SubBatchPipeline[Item, Any](
        fileGen  = _fileGen.getOrElse(throw new IllegalStateException(s"[$chainId] fileGen not configured")),
        upload   = _upload.getOrElse(throw new IllegalStateException(s"[$chainId] upload not configured")),
        waitAck  = _waitAck.getOrElse(throw new IllegalStateException(s"[$chainId] waitAck not configured")),
        pollResp = _pollResp.getOrElse(throw new IllegalStateException(s"[$chainId] pollResp not configured")),
        parse    = _parse.getOrElse(throw new IllegalStateException(s"[$chainId] parse not configured"))
          .asInstanceOf[ResponseParser[Any]],
        classify = _classify.getOrElse(throw new IllegalStateException(s"[$chainId] classify not configured"))
          .asInstanceOf[ResultClassifier[Any, Item]]
      )

      ChainDefinition(
        chainId    = chainId,
        pipeline   = pipeline,
        reconfirm  = _reconfirm.getOrElse(new NoopReconfirmHandler[Item]),
        router     = new PolicyBasedReBatchRouter[Item](_routerPolicy),
        scheduler  = new WindowedAreaScheduler[Item](_constraints) {
          override def generateBatchId(): String = s"$chainId-${System.currentTimeMillis()}"
        }
      )
    }
  }

  // ---- Failure Router Builder ----

  class FailureRouterBuilder {
    private var _maxRetries: Int = 3
    private var _defaultCooldown: FiniteDuration = 5.minutes
    private val _actionMap = scala.collection.mutable.Map.empty[String, NextStep]
    private var _defaultAction: Option[NextStep] = None

    def maxRetries(n: Int): Unit = { _maxRetries = n }
    def cooldown(d: FiniteDuration): Unit = { _defaultCooldown = d }

    def when(code: String)(action: => NextStep): Unit = {
      _actionMap(code) = action
    }
    def otherwise(action: => NextStep): Unit = {
      _defaultAction = Some(action)
    }

    def build(): ReBatchPolicy = {
      val fullMap = _defaultAction match {
        case Some(default) =>
          _actionMap.toMap.withDefaultValue(default)
        case None =>
          _actionMap.toMap
      }
      ReBatchPolicy(
        maxRetries = _maxRetries,
        actionMap = fullMap,
        defaultCooldown = _defaultCooldown
      )
    }
  }

  // ---- Scheduling Builder ----

  class SchedulingBuilder {
    private var _minBatchSize: Int = 1
    private var _maxBatchSize: Int = 100
    private var _carrierCapacity: Int = 0
    private var _batchWindow: FiniteDuration = 10.minutes
    private var _allowMixedSources: Boolean = true

    def minBatchSize(n: Int): Unit = { _minBatchSize = n }
    def maxBatchSize(n: Int): Unit = { _maxBatchSize = n }
    def carrierCapacity(n: Int): Unit = { _carrierCapacity = n }
    def batchWindow(d: FiniteDuration): Unit = { _batchWindow = d }
    def allowMixedSources(b: Boolean): Unit = { _allowMixedSources = b }

    def build(): PhysicalConstraints = PhysicalConstraints(
      minBatchSize = _minBatchSize,
      maxBatchSize = _maxBatchSize,
      carrierCapacity = _carrierCapacity,
      batchWindow = _batchWindow,
      allowMixedSources = _allowMixedSources
    )
  }

  // ---- Default/noop reconfirm handler ----

  /** 默认复核处理器——可疑项直接标记为失败 */
  class NoopReconfirmHandler[Item] extends ReconfirmHandler[Item] {
    override def reconfirm(suspicious: Seq[Suspicious[Item]]): Future[Seq[Classification[Item]]] =
      Future.successful(suspicious.map(s =>
        Failure(s.item, FailureReason(s.reason.code, s"Unresolved: ${s.reason.message}"))))
  }

  // ---- Chain Definition (the assembled result) ----

  /**
   * 一条完整的业务链路定义——可直接提交执行。
   *
   * 六个机械阶段由 monarch-core 的 Monarch 引擎驱动（BankChain 六阶段队列），
   * 可疑复核与失败路由保持在引擎之外（业务编排层）。
   */
  case class ChainDefinition[Item](
      chainId: String,
      pipeline: SubBatchPipeline[Item, Any],
      reconfirm: ReconfirmHandler[Item],
      router: ReBatchRouter[Item],
      scheduler: AreaScheduler[Item]
  ) {
    /** 处理一个小批次：六阶段队列（Monarch）→ 可疑复核 → 失败路由。 */
    def processBatch(items: Seq[Item], source: ItemSource = ItemSource.NewArrival)
                    (implicit ec: ExecutionContext): Future[SubBatchResult[Classification[Item]]] = {
      val batchId = s"$chainId-${System.currentTimeMillis()}"
      val monarch = BankChain.monarch[Item, Any](pipeline,
        hooks = new LifecycleHooks[BankStage, BankChainState[Item, Any]] {
          override def stageName(stage: BankStage): String = BankStage.stageName(stage)
        })
      monarch.initialize(BankStage.chain)

      for {
        finalState <- monarch.process(BankChainState[Item, Any](
          batchId = batchId, chainId = chainId, items = items))
        classifications = finalState.classifications.getOrElse(Seq.empty)
        result = SubBatchResult[Classification[Item]](batchId,
          classifications.collect { case s: Success[Item] => s },
          classifications.collect { case f: Failure[Item] => f },
          classifications.collect { case s: Suspicious[Item] => s })
        // 可疑项复核
        resolved <- if (result.suspicious.nonEmpty) {
          val suspicious = result.suspicious.collect { case s: Suspicious[Item] => s }
          reconfirm.reconfirm(suspicious).map { resolved =>
            val newSuccesses = resolved.collect { case s: Success[Item] => s }
            val newFailures  = resolved.collect { case f: Failure[Item] => f }
            SubBatchResult(result.batchId,
              result.successes ++ newSuccesses,
              result.failures ++ newFailures,
              Seq.empty)
          }
        } else Future.successful(result)
        // 失败项路由
        _ <- if (resolved.failures.nonEmpty) {
          val failed = resolved.failures.collect { case f: Failure[Item] => f }
          router.route(failed, ProcessContext(chainId, 0, Some(batchId)))
            .flatMap { decisions =>
              val retryItems = decisions.collect {
                case RoutingDecision(item, NextStep.RetrySameArea(_), _) => item
              }
              if (retryItems.nonEmpty) scheduler.submit(retryItems, ItemSource.ReBatch(chainId))
              else Future.successful(())
            }
        } else Future.successful(())
      } yield resolved
    }
  }

  // ============================================================
  // Convenience constructors for common patterns
  // ============================================================

  /** 创建基于错误码的分类器 */
  def errorCodeClassifier[Raw, Item](
      extractCodeFn: Raw => String,
      associateFn: (Raw, Seq[Item]) => Option[Item],
      mapping: ErrorCodeMapping = ErrorCodeMapping.empty
  ): ErrorCodeBasedClassifier[Raw, Item] = new ErrorCodeBasedClassifier[Raw, Item] {
    override def errorCodeMapping: ErrorCodeMapping = mapping
    override def extractCode(raw: Raw): String = extractCodeFn(raw)
    override def associateItem(raw: Raw, items: Seq[Item]): Option[Item] = associateFn(raw, items)
  }

  // ============================================================
  // Simple routing DSL
  // ============================================================

  /** 快速路由策略构造 */
  def routePolicy(build: FailureRouterBuilder => Unit): ReBatchPolicy = {
    val b = new FailureRouterBuilder
    build(b)
    b.build()
  }

  /** 快速调度约束构造 */
  def constraints(build: SchedulingBuilder => Unit): PhysicalConstraints = {
    val b = new SchedulingBuilder
    build(b)
    b.build()
  }
}
