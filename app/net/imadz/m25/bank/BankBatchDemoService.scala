package net.imadz.m25.bank

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.util.Timeout
import javax.inject.{Inject, Singleton}
import net.imadz.m25.component._
import net.imadz.monarch.{LifecycleHooks, RunRegistry}
import play.api.Logging

import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.language.postfixOps
import scala.util.{Failure, Success}

/** 队列里的一个条目 = 客户 + 它所属的链与轮次（链信息随 items 走，一套流水线服务两条链）。 */
case class ChainItem(c: DemoCustomer, chain: String, round: Int)

/** 一笔充值/申购请求。 */
case class DemoCustomer(idx: Int, customerId: String, name: String, amount: Double, fund: String)

/**
 * 银行批量充值 + 申购演示（规模版）：100,000 客户 · 2,000/批 · 50 批 · 并发 6。
 *
 * 两条链共用 monarch-core 的 Monarch 六阶段骨架（ChainExecutionActor 每批一个实体）：
 *   - 充值链 recharge（合作银行）：充值文件交换六工序 → 成功项更新理财账户
 *   - 申购链 purchase（基金公司）：充值到账客户自动发起申购 → 成功项扣减余额、增加基金持仓
 *
 * 剧本（确定性哈希，可复现）：
 *   - 第 1 轮约 2% 拒绝（余额不足/额度超限 → 按策略放弃）、约 2% 超时（→ 可疑 → 查证：
 *     60% 确认成功、40% 网络类失败 → 自动重批）；第 2 轮 90% 成功、10% 仍超时 → 第 3 轮
 *     触发 maxRetries 上限 → 转人工工单。
 *   - 账务更新（入账/扣款/持仓）注入 1% 瞬时故障 → 自动重试一次 → 仍失败进人工异常池。
 */
@Singleton
class BankBatchDemoService @Inject()(
    classicSystem: akka.actor.ActorSystem,
    sharding: ClusterSharding,
    implicit val mat: akka.stream.Materializer
)(implicit ec: ExecutionContext) extends Logging {

  implicit val system: ActorSystem[Nothing] =
    akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps(classicSystem).toTyped
  private implicit val askTimeout: Timeout = Timeout(30.seconds)

  // ====================================================================
  // 规模与客户
  // ====================================================================
  val TOTAL_CUSTOMERS = 100000
  val BATCH_SIZE = 2000
  val TOTAL_BATCHES = TOTAL_CUSTOMERS / BATCH_SIZE
  val MAX_CONCURRENT_BATCHES = 6
  private val FUNDS = Seq("成长混合A", "稳健增利B", "科技创新C")

  lazy val customers: Vector[DemoCustomer] = (0 until TOTAL_CUSTOMERS).toVector.map { i =>
    DemoCustomer(i, uuidOf(s"c-$i"), f"客户$i%06d",
      100 + (hash(s"amt-$i") % 4900) + 0.5, FUNDS(i % FUNDS.size))
  }
  private lazy val customerById: Map[String, DemoCustomer] = customers.map(c => c.customerId -> c).toMap
  private def uuidOf(seed: String): String = java.util.UUID.nameUUIDFromBytes(seed.getBytes).toString
  private def hash(s: String): Int = math.abs(s.hashCode)

  private def sliceOf(index: Int): Vector[DemoCustomer] =
    customers.slice(index * BATCH_SIZE, (index + 1) * BATCH_SIZE)

  // ====================================================================
  // 确定性结果（同一客户/链/轮次永远同一结果，断点续跑与重算可复现）
  // ====================================================================

  /** OK / TIMEOUT / 拒绝码（recharge: BALANCE_INSUFFICIENT；purchase: QUOTA_EXCEEDED）。 */
  def outcomeOf(chain: String, c: DemoCustomer, round: Int): String = {
    val v = hash(s"$chain-${c.customerId}-r$round") % 100
    val reject = if (chain == "recharge") "BALANCE_INSUFFICIENT" else "QUOTA_EXCEEDED"
    if (round == 1) {
      if (v < 2) reject else if (v < 4) "TIMEOUT" else "OK"
    } else {
      if (v < 10) "TIMEOUT" else "OK"
    }
  }

  /** 查证结论：60% 确认成功，40% 网络类失败（建议自动重批）。 */
  def reconfirmOf(chain: String, c: DemoCustomer): String = {
    val v = hash(s"$chain-verify-${c.customerId}") % 100
    if (v < 60) "VERIFIED_SUCCESS" else "NETWORK_ERROR"
  }

  private def rejectCodeOf(chain: String): String =
    if (chain == "recharge") "BALANCE_INSUFFICIENT" else "QUOTA_EXCEEDED"

  // ====================================================================
  // 计数器（统计视图数据源）
  // ====================================================================
  private val counters = new ConcurrentHashMap[String, AtomicLong]()
  private def counter(name: String): AtomicLong =
    counters.computeIfAbsent(name, _ => new AtomicLong(0))
  private def inc(name: String, by: Long = 1): Unit = counter(name).addAndGet(by)
  def counterValue(name: String): Long = Option(counters.get(name)).map(_.get).getOrElse(0L)

  // ====================================================================
  // 模拟核心账务系统（1% 瞬时故障 → 自动重试一次 → 人工异常池）
  // ====================================================================
  private val accountBalances = new ConcurrentHashMap[String, java.lang.Double]()
  private val fundPositions = new ConcurrentHashMap[String, java.lang.Double]()

  /** 充值入账 / 申购扣款+持仓。返回 false = 两次尝试均失败，已进人工异常池。 */
  private def updateAccountGlitchy(ci: ChainItem): Future[Boolean] = {
    def once(): Future[Boolean] =
      if (hash(s"glitch-${ci.chain}-${ci.c.customerId}") % 100 < 1)
        Future.failed(new RuntimeException("账务系统瞬时故障"))
      else Future {
        if (ci.chain == "recharge") {
          accountBalances.merge(ci.c.customerId, ci.c.amount, (a, b) => java.lang.Double.valueOf(a.doubleValue + b.doubleValue))
          inc("recharge_amount", ci.c.amount.toLong)
        } else {
          accountBalances.merge(ci.c.customerId, -ci.c.amount, (a, b) => java.lang.Double.valueOf(a.doubleValue + b.doubleValue))
          fundPositions.merge(ci.c.customerId, ci.c.amount, (a, b) => java.lang.Double.valueOf(a.doubleValue + b.doubleValue))
          inc("purchase_amount", ci.c.amount.toLong)
          inc("purchase_position_amount", ci.c.amount.toLong)
        }
        true
      }
    once().recoverWith {
      case _ => inc(s"${ci.chain}_account_retry"); akka.pattern.after(200.millis)(once())
    }.recover {
      case _ => inc(s"${ci.chain}_account_manual"); false
    }
  }

  // ====================================================================
  // 事件流（页面滚动显示，最多 40 条）
  // ====================================================================
  private val feed = new mutable.Queue[String]()
  private def feedAdd(line: String): Unit = synchronized {
    feed.enqueue(s"${java.time.LocalTime.now().withNano(0)}  $line")
    if (feed.size > 40) feed.dequeue()
  }
  def feedSnapshot: List[String] = synchronized(feed.toList)

  private val (hubSink, hubSource) =
    akka.stream.scaladsl.MergeHub.source[String](256)
      .toMat(akka.stream.scaladsl.BroadcastHub.sink[String](bufferSize = 1024))(akka.stream.scaladsl.Keep.both)
      .run()(mat)
  private val frameSeq = new AtomicLong(0)
  private def publish(`type`: String, data: Map[String, String]): Unit = {
    val json = BankBatchJson.frame(frameSeq.incrementAndGet(), `type`, data)
    akka.stream.scaladsl.Source.single(json).runWith(hubSink)
  }
  def source: akka.stream.scaladsl.Source[String, _] = hubSource

  // ====================================================================
  // 批次队列与调度（限流并发；充值链收尾自动衔接申购链）
  // ====================================================================
  case class BatchJob(chain: String, round: Int, index: Int)

  private val queue = new ConcurrentLinkedQueue[BatchJob]()
  private val runningCount = new AtomicInteger(0)
  private val runningBatches = new ConcurrentHashMap[String, BatchJob]()
  private val purchaseSeq = new AtomicInteger(0)

  /** 每次全量运行的唯一 id：实体名带 runId，journal 永不跨运行串台。 */
  private var runId: String = "boot"

  def enqueue(job: BatchJob): Unit = { queue.add(job); pump() }

  private def pump(): Unit =
    while (runningCount.get() < MAX_CONCURRENT_BATCHES) {
      val job = queue.poll()
      if (job == null) return
      runningCount.incrementAndGet()
      runningBatches.put(jobKey(job), job)
      startJob(job)
    }

  private def jobKey(job: BatchJob): String = s"bank-$runId-${job.chain}-r${job.round}-b${job.index}"

  private def startJob(job: BatchJob): Unit = {
    val items = itemsFor(job.chain, job.round, job.index)
    publish("job-start", Map("chain" -> job.chain, "round" -> job.round.toString,
      "index" -> job.index.toString, "items" -> items.size.toString))
    val entityRef = sharding.entityRefFor(ChainExecutionActor.EntityKey, jobKey(job))
    import akka.actor.typed.scaladsl.AskPattern._
    entityRef.ask(ref => ChainExecutionActor.StartExecution(
      s"${job.chain}-r${job.round}-b${job.index}", items.toList, ref)).foreach {
      case ChainExecutionActor.Accepted(_) => ()
      case ChainExecutionActor.ExecutionRejected(_, _, reason) =>
        // 被拒（实体已完成/失败）必须释放并发槽，否则调度器死锁
        logger.warn(s"[bank-demo] StartExecution rejected (job=$job): $reason")
        jobDone(job)
    }
  }

  private def parseBatchKey(key: String): (String, Int, Int) = {
    val parts = key.stripPrefix("bank-").split("-")
    (parts(0), parts(1).drop(1).toInt, parts(2).drop(1).toInt)
  }

  /** 断点续跑与重批轮的确定性取数。 */
  def itemsFor(chain: String, round: Int, index: Int): Vector[ChainItem] = {
    val slice = sliceOf(index)
    val base: Vector[DemoCustomer] =
      if (round == 1) slice
      else slice.filter(c => outcomeOf(chain, c, 1) == "TIMEOUT" && reconfirmOf(chain, c) == "NETWORK_ERROR")
    base.map(c => ChainItem(c, chain, round))
  }

  private def jobDone(job: BatchJob): Unit = {
    runningBatches.remove(jobKey(job))
    runningCount.decrementAndGet()
    publish("progress", Map(
      "running" -> runningCount.get().toString, "queued" -> queue.size().toString,
      "done" -> counterValue("batches_done").toString))
    pump()
  }

  // ====================================================================
  // 统一流水线（链与轮次从 ChainItem / ctx 读取）+ 统一观察者
  // ====================================================================

  private def classifyOne(ci: ChainItem): Classification[Any] =
    outcomeOf(ci.chain, ci.c, ci.round) match {
      case "OK"      => net.imadz.m25.component.Success[Any](ci.c.customerId, ci.c.customerId)
      case "TIMEOUT" => net.imadz.m25.component.Suspicious[Any](ci.c.customerId,
        net.imadz.m25.component.SuspiciousReason("TIMEOUT", "外部系统处理超时"))
      case other     => net.imadz.m25.component.Failure[Any](ci.c.customerId,
        net.imadz.m25.component.FailureReason(other, other, None))
    }

  private val unifiedPipeline: SubBatchPipeline[Any, Any] = {
    def after[T](ms: Int)(f: => T): Future[T] = Future { Thread.sleep(ms); f }
    def chainOf(ctx: Map[String, Any]): String =
      ctx.getOrElse("chainId", "recharge-r1-b0").toString.stripPrefix("bank-").split("-")(0)
    def roundOf(ctx: Map[String, Any]): Int =
      ctx.getOrElse("chainId", "recharge-r1-b0").toString.split("-")(1).drop(1).toInt
    SubBatchPipeline[Any, Any](
      fileGen = (items, ctx) => after(120) {
        val batchId = ctx.getOrElse("batchId", "?").toString
        GeneratedFile(s"/tmp/$batchId.dat", s"$batchId.dat", items.size * 256L, "dat")
      },
      upload = (file, _) => after(120)(UploadReceipt(s"sftp://host/inbound/${file.fileName}", file.byteSize, System.currentTimeMillis())),
      waitAck = (_, _) => after(100)(AckReceived),
      pollResp = ctx => after(180) {
        val batchId = ctx.getOrElse("batchId", "?").toString
        val content = s"response-of-$batchId".getBytes
        ResponseReady(ResponseFile(s"/tmp/resp-$batchId.dat", s"resp-$batchId.dat", content.length.toLong, content))
      },
      parse = (_, _) => after(120)(Seq("raw")),
      classify = (_, items) => after(100)(items.collect {
        case ci: ChainItem => classifyOne(ci)
      })
    )
  }

  private val unifiedItemLoader: String => Future[Seq[Any]] = batchId => {
    val (chain, round, index) = parseBatchKey(batchId)
    Future.successful(itemsFor(chain, round, index).toList)
  }

  private val unifiedObserver = new ChainExecutionActor.ChainExecutionObserver with Logging {
    override def onStart(batchId: String, itemCount: Int): Unit = {
      val (chain, _, _) = parseBatchKey(batchId)
      publish("batch-started", Map("chain" -> chain, "batchId" -> batchId, "items" -> itemCount.toString))
    }
    override def onStageStart(cursor: String): Unit =
      publish("stage-start", Map("cursor" -> cursor))
    override def onStageComplete(cursor: String, metadata: Map[String, String],
                                  snapshot: Option[BankChainState[Any, Any]]): Unit =
      publish("stage-done", Map("chain" -> chainKeyOfBatch(snapshot, cursor), "cursor" -> cursor))
    override def onStageFailed(cursor: String, detail: String): Unit =
      publish("stage-failed", Map("cursor" -> cursor, "detail" -> detail))
    override def onRecovery(batchId: String, completedPhases: Int): Unit = {
      inc("recoveries")
      publish("recovering", Map("batchId" -> batchId, "completed" -> completedPhases.toString))
      feedAdd(s"[$batchId] 宕机恢复：前 $completedPhases 道不重做，从断点续跑")
    }
    override def onCompleted(batchId: String, snapshot: Option[BankChainState[Any, Any]]): Unit = {
      // 立即移出运行表：AllCompleted 已持久化，宕机按钮不应再瞄准本批
      val (chain, round, index) = parseBatchKey(batchId)
      runningBatches.remove(jobKey(BatchJob(chain, round, index)))
      inc("batches_done")
      publish("batch-completed", Map("batchId" -> batchId))
      snapshot.foreach { st => businessClosure(chain, round, index, st) } // closure 结束时再 pump
    }
    override def onFailed(batchId: String, phase: String, reason: String): Unit = {
      publish("batch-failed", Map("batchId" -> batchId, "phase" -> phase, "reason" -> reason))
      feedAdd(s"[$batchId] 失败@$phase：$reason")
      jobDoneByBatchId(batchId)
    }
    override def onCrash(batchId: String, reason: String): Unit = {
      publish("crash", Map("batchId" -> batchId, "reason" -> reason))
      feedAdd(s"[$batchId] ⚡ 宕机注入：$reason（账本已保存，断点续跑）")
    }
  }

  private def chainKeyOfBatch(snapshot: Option[BankChainState[Any, Any]], fallback: String): String =
    snapshot.flatMap(_.items.collectFirst { case ChainItem(_, chain, _) => chain }).getOrElse(fallback)

  private def jobDoneByBatchId(batchId: String): Unit = {
    val (chain, round, index) = parseBatchKey(batchId)
    jobDone(BatchJob(chain, round, index))
  }

  // ====================================================================
  // 批次收尾业务编排：查证 → 失败路由（重批/放弃/人工）→ 账务更新 → 充值链衔接申购链
  // ====================================================================

  private val policies = Map(
    "recharge" -> ReBatchPolicy(
      maxRetries = 2,
      actionMap = Map("BALANCE_INSUFFICIENT" -> NextStep.Scrap,
        "NETWORK_ERROR" -> NextStep.RetrySameArea(3.seconds)),
      defaultCooldown = 3.seconds),
    "purchase" -> ReBatchPolicy(
      maxRetries = 2,
      actionMap = Map("QUOTA_EXCEEDED" -> NextStep.Scrap,
        "NETWORK_ERROR" -> NextStep.RetrySameArea(3.seconds)),
      defaultCooldown = 3.seconds))

  private val routers: Map[String, PolicyBasedReBatchRouter[Any]] = policies.map { case (k, p) => k -> new PolicyBasedReBatchRouter[Any](p) }

  private def businessClosure(chain: String, round: Int, index: Int, state: BankChainState[Any, Any]): Unit = {
    val cs = state.classifications.getOrElse(Seq.empty)
    inc(s"${chain}_total", cs.size.toLong)

    val successes = cs.collect { case s: net.imadz.m25.component.Success[Any] => s }
    val suspicious = cs.collect { case s: net.imadz.m25.component.Suspicious[Any] => s }
    val failures = cs.collect { case f: net.imadz.m25.component.Failure[Any] => f }

    inc(s"${chain}_ok", successes.size.toLong)
    inc(s"${chain}_suspicious", suspicious.size.toLong)

    // 1) 可疑 → 查证落定（60% 确认成功；40% 网络类失败 → 建议自动重批）
    val confirmedFailures = suspicious.collect {
      case s if reconfirmOf(chain, customerById(s.item.toString)) == "VERIFIED_SUCCESS" =>
        inc(s"${chain}_reconfirm_ok")
        feedAdd(s"[${chainOfWord(chain)}·查证] ${customerById(s.item.toString).name} 确认成功 → 转入成功")
        // 查证成功视为成功项：直接走账务更新
        updateAccountGlitchy(ChainItem(customerById(s.item.toString), chain, round)).foreach { ok =>
          if (ok) inc(s"${chain}_account_ok") else inc(s"${chain}_account_manual")
        }
        None
      case s =>
        val c = customerById(s.item.toString)
        inc(s"${chain}_reconfirm_network")
        feedAdd(s"[${chainOfWord(chain)}·查证] ${c.name} 外部系统拥堵 → 网络类失败，自动重批")
        Some(net.imadz.m25.component.Failure[Any](c.customerId, net.imadz.m25.component.FailureReason(
          "NETWORK_ERROR", "外部系统拥堵", Some(NextStep.RetrySameArea(3.seconds)))))
    }.flatten

    // 2) 失败 → 路由决策（重批 / 放弃 / 人工）
    val allFailures = failures ++ confirmedFailures
    if (allFailures.nonEmpty) {
      val policy = policies(chain)
      val ctx = ProcessContext(currentAreaId = chain, retryCount = round - 1, originalBatchId = Some(s"b$index"))
      routers(chain).route(allFailures, ctx).foreach { decisions =>
        var needRebatch = false
        var minDelay = policy.defaultCooldown
        decisions.foreach { d =>
          val c = customerById(d.item.toString)
          d.nextStep match {
            case NextStep.Scrap =>
              inc(s"${chain}_rejected")
              feedAdd(s"[${chainOfWord(chain)}·放弃] ${c.name}：${d.reason}")
            case NextStep.RetrySameArea(delay) =>
              inc(s"${chain}_rebatched")
              needRebatch = true
              if (delay < minDelay) minDelay = delay
              feedAdd(s"[${chainOfWord(chain)}·重批] ${c.name} ${delay} 后进入第 ${round + 1} 轮")
            case NextStep.ManualIntervention(ticket) =>
              inc(s"${chain}_manual")
              feedAdd(s"[${chainOfWord(chain)}·人工] ${c.name} 工单 $ticket（重试上限）")
            case NextStep.RouteToArea(area, _) =>
              feedAdd(s"[${chain}] ${c.name} → 路由 $area")
          }
        }
        // 整批只入队一次下一轮（逐笔入队会产生成百上千个重复批 → 调度死锁）
        if (needRebatch) {
          classicSystem.scheduler.scheduleOnce(minDelay)(enqueue(BatchJob(chain, round + 1, index)))
        }
      }
    }

    // 3) 成功项 → 账务更新（1% 瞬时故障 → 自动重试 → 人工异常池）
    successes.foreach { s =>
      val c = customerById(s.item.toString)
      updateAccountGlitchy(ChainItem(c, chain, round)).foreach { ok =>
        if (ok) inc(s"${chain}_account_ok")
        else inc(s"${chain}_account_manual")
      }
    }

    // 4) 充值链收尾 → 已入账客户自动衔接申购链（两条链由此串联）
    if (chain == "recharge") {
      val credited = successes.map(s => customerById(s.item.toString)).toVector
      credited.grouped(BATCH_SIZE).foreach { group =>
        val idx = purchaseSeq.getAndIncrement()
        enqueue(BatchJob("purchase", 1, idx))
        feedAdd(s"[衔接] 充值到账 ${group.size} 笔 → 自动发起基金申购（purchase-b$idx）")
      }
    }

    jobDone(BatchJob(chain, round, index))
  }

  private def chainOfWord(chain: String): String = if (chain == "recharge") "充值" else "申购"

  // ====================================================================
  // Public API
  // ====================================================================

  def initSharding(): Unit =
    ChainExecutionActor.init(sharding, unifiedPipeline, unifiedItemLoader, unifiedObserver)

  def startRun(): Map[String, Any] = {
    if (runningCount.get() > 0 || queue.size() > 0)
      return Map("started" -> false, "message" -> "已有批量任务在运行")
    counters.clear()
    queue.clear()
    runningBatches.clear()
    runningCount.set(0)
    accountBalances.clear()
    fundPositions.clear()
    purchaseSeq.set(0)
    feed.clear()
    runId = java.util.UUID.randomUUID().toString.take(8)
    customers // 触发初始化
    (0 until TOTAL_BATCHES).foreach(i => enqueue(BatchJob("recharge", 1, i)))
    publish("run-started", Map("customers" -> TOTAL_CUSTOMERS.toString,
      "batchSize" -> BATCH_SIZE.toString, "batches" -> TOTAL_BATCHES.toString))
    feedAdd(s"全量启动：$TOTAL_CUSTOMERS 客户 · 每批 $BATCH_SIZE · 共 $TOTAL_BATCHES 批 · 并发 $MAX_CONCURRENT_BATCHES")
    Map("started" -> true, "customers" -> TOTAL_CUSTOMERS, "batchSize" -> BATCH_SIZE, "batches" -> TOTAL_BATCHES)
  }

  def crashRandom(): Map[String, Any] = {
    val keys = runningBatches.keySet().asScala.toList
    if (keys.isEmpty) Map("crashed" -> false, "message" -> "当前没有运行中的批次")
    else {
      val pick = keys(hash(java.time.LocalTime.now().toString + keys.hashCode) % keys.size)
      val job = runningBatches.get(pick)
      sharding.entityRefFor(ChainExecutionActor.EntityKey, pick) ! ChainExecutionActor.StopPipeline("演示按钮触发")
      Map("crashed" -> true, "batchId" -> pick,
        "message" -> s"已向运行中的批次注入宕机（${job.chain} r${job.round} b${job.index}），断点续跑即将开始")
    }
  }

  // ====================================================================
  // 统计快照
  // ====================================================================

  def chainStatsJson(chain: String, label: String): play.api.libs.json.JsValue = {
    import play.api.libs.json._
    Json.obj(
      "label" -> label,
      "total" -> counterValue(s"${chain}_total"),
      "ok" -> counterValue(s"${chain}_ok"),
      "rejected" -> counterValue(s"${chain}_rejected"),
      "suspicious" -> counterValue(s"${chain}_suspicious"),
      "reconfirmOk" -> counterValue(s"${chain}_reconfirm_ok"),
      "networkRetry" -> counterValue(s"${chain}_reconfirm_network"),
      "rebatched" -> counterValue(s"${chain}_rebatched"),
      "manual" -> counterValue(s"${chain}_manual"),
      "accountOk" -> counterValue(s"${chain}_account_ok"),
      "accountRetry" -> counterValue(s"${chain}_account_retry"),
      "accountManual" -> counterValue(s"${chain}_account_manual"),
      "amount" -> counterValue(s"${chain}_amount"))
  }

  def statsJson: String = {
    import play.api.libs.json._
    Json.stringify(Json.obj(
      "scale" -> Json.obj(
        "customers" -> TOTAL_CUSTOMERS, "batchSize" -> BATCH_SIZE,
        "batches" -> TOTAL_BATCHES, "concurrency" -> MAX_CONCURRENT_BATCHES),
      "batches" -> Json.obj(
        "running" -> runningCount.get(), "queued" -> queue.size(),
        "done" -> counterValue("batches_done"), "recoveries" -> counterValue("recoveries")),
      "recharge" -> chainStatsJson("recharge", "充值链（合作银行）"),
      "purchase" -> chainStatsJson("purchase", "申购链（基金公司）"),
      "amounts" -> Json.obj(
        "rechargeCredited" -> counterValue("recharge_amount"),
        "purchasePaid" -> counterValue("purchase_amount"),
        "position" -> counterValue("purchase_position_amount")),
      "account" -> Json.obj(
        "retry" -> (counterValue("recharge_account_retry") + counterValue("purchase_account_retry")),
        "manual" -> (counterValue("recharge_account_manual") + counterValue("purchase_account_manual"))),
      "feed" -> feedSnapshot))
  }
}
