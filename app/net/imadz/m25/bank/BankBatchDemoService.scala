package net.imadz.m25.bank

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.stream.Materializer
import akka.util.Timeout
import javax.inject.{Inject, Singleton}
import net.imadz.application.aggregates.CreditBalanceProtocol
import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.application.services.{CreateCreditBalanceService, DepositService}
import net.imadz.m25.component._
import net.imadz.monarch.LifecycleHooks
import net.imadz.domain.values.Money
import play.api.Logging

import java.util.Currency
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

/** One fund-subscription order in the demo batch. */
case class RechargeOrder(orderNo: String, customerName: String, customerId: String, amount: Double)

/** Demo JSON frame for the WebSocket page. */
case class BankBatchFrame(seq: Long, payload: Map[String, Any])

/**
 * 银行批量充值演示服务——白话教程《一次批量充值批的奇幻漂流》的可交互版本。
 *
 * 剧本（5 笔充值，从银行卡充进理财账户）：
 *   - 充值 1/2/3：发卡行扣款成功，触发 M1 入账（Deposit 到 CreditBalance 聚合，业务闭环）
 *   - 充值 4：银行卡余额不足 → 分类为失败 → 重批策略 Scrap（放弃，演示"不该重试的别重试"）
 *   - 充值 5：银行返回 TIMEOUT → 可疑 → 查证（模拟核心系统查询）确认"系统拥堵未成功"
 *     → 归类为 NETWORK_ERROR 失败 → 重批策略 RetrySameArea(3s) → 第二轮重批成功并入账
 *
 * 全程经 ChainExecutionActor（事件溯源 + 断点续跑 + 世代守卫），⚡按钮注入宕机演示自愈。
 */
@Singleton
class BankBatchDemoService @Inject()(
    classicSystem: akka.actor.ActorSystem,
    sharding: ClusterSharding,
    depositService: DepositService,
    createCreditBalanceService: CreateCreditBalanceService,
    creditBalanceRepository: CreditBalanceRepository,
    implicit val mat: Materializer
)(implicit ec: ExecutionContext) extends Logging {

  implicit val system: ActorSystem[Nothing] = akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps(classicSystem).toTyped
  private implicit val askTimeout: Timeout = Timeout(30.seconds)

  val chainId: String = "recharge-demo"
  private val attemptCounter = new ConcurrentHashMap[String, Int]()
  private val attemptsOf = new AtomicInteger(0)

  // ---- Demo state for the page (GET /api/bank-batch/state) ----
  @volatile var orders: Seq[RechargeOrder] = Seq.empty
  @volatile var batchId: String = ""
  @volatile var currentStage: String = ""
  @volatile var doneStages: List[String] = List.empty
  @volatile var itemStates: Map[String, String] = Map.empty       // orderNo → 中文状态
  @volatile var balances: Map[String, String] = Map.empty         // customer → 余额文本
  val ledger: java.util.List[String] = java.util.Collections.synchronizedList(new java.util.ArrayList[String]())

  private def ledgerAdd(line: String): Unit = {
    ledger.add(s"${java.time.LocalTime.now().withNano(0)}  $line")
    if (ledger.size() > 60) ledger.remove(0)
  }

  // ====================================================================
  // WebSocket hub (same pattern as FabDemoController)
  // ====================================================================
  private val (hubSink, hubSource) =
    akka.stream.scaladsl.MergeHub.source[String](256)
      .toMat(akka.stream.scaladsl.BroadcastHub.sink[String](bufferSize = 1024))(akka.stream.scaladsl.Keep.both)
      .run()(mat)

  private val frameSeq = new java.util.concurrent.atomic.AtomicLong(0)
  def publish(`type`: String, data: Map[String, Any]): Unit = {
    val json = net.imadz.m25.bank.BankBatchJson.frame(frameSeq.incrementAndGet(), `type`, data)
    akka.stream.scaladsl.Source.single(json).runWith(hubSink)(mat)
  }
  def source: akka.stream.scaladsl.Source[String, _] = hubSource

  // ====================================================================
  // Mock bank pipeline: six stages with business-meaningful outcomes
  // ====================================================================

  /** 每笔单子的"基金公司返回码"，按轮次变化：第 1 轮申购 5 超时；重批轮全部放行。 */
  private def bankResultOf(orderNo: String): String = {
    val attempt = Option(attemptCounter.get(orderNo)).getOrElse(1)
    orderNo match {
      case "order-4" => "BALANCE_INSUFFICIENT"
      case "order-5" if attempt < 2 => "TIMEOUT"
      case _ => "OK"
    }
  }

  private def ordersOf(state: BankChainState[Any, Any]): Seq[RechargeOrder] =
    state.items.collect { case o: RechargeOrder => o }

  private val demoPipeline: SubBatchPipeline[Any, String] = {
    def delay[T](ms: Long)(f: => T): Future[T] = Future { Thread.sleep(ms); f }
    SubBatchPipeline[Any, String](
      fileGen = (items, ctx) => delay(700) {
        val batchId = ctx.getOrElse("batchId", "?").toString
        GeneratedFile(s"/tmp/$batchId.xml", s"$batchId.xml", items.size * 128L, "xml")
      },
      upload = (file, _) => delay(700) {
        UploadReceipt(s"sftp://fund-co/inbound/${file.fileName}", file.byteSize, System.currentTimeMillis())
      },
      waitAck = (_, _) => delay(600)(AckReceived),
      pollResp = ctx => delay(900) {
        val batchId = ctx.getOrElse("batchId", "?").toString
        val lines = orders.map(o => s"${bankResultOf(o.orderNo)}-${o.orderNo}")
        val content = lines.mkString("\n").getBytes
        ResponseReady(ResponseFile(s"/tmp/response-$batchId.xml", s"response-$batchId.xml", content.length.toLong, content))
      },
      parse = (file, _) => delay(500) {
        new String(file.content).linesIterator.toSeq
      },
      classify = (_, items) => delay(400) {
        // 只分类【本批】items：重批轮的批里只有重试单，否则会把其他单再入账一遍
        items.collect { case o: RechargeOrder =>
          val raw = s"${bankResultOf(o.orderNo)}-${o.orderNo}"
          bankResultOf(o.orderNo) match {
            case "OK"      => Success[Any](raw, raw)
            case "TIMEOUT" => Suspicious[Any](raw, net.imadz.m25.component.SuspiciousReason("TIMEOUT", "银行处理超时"))
            case other     => Failure[Any](raw, net.imadz.m25.component.FailureReason(other, other, None))
          }
        }
      }
    )
  }

  // ====================================================================
  // Business closure: reconfirm (查证) + rebatch decision + credit (入账)
  // ====================================================================

  /** 可疑查证：模拟"调基金公司核心系统查询"。第 1 轮申购 5 查证为"系统拥堵未成功"
    * （网络类失败 → 建议重批）；第 2 轮不会再有可疑（mock 直接放行）。 */
  private val reconfirmHandler: ReconfirmHandler[String] = (suspicious: Seq[Suspicious[String]]) =>
    Future.successful(suspicious.map { s =>
      val orderNo = demoOrdersIn(s.item.toString)
      if (attemptCounter.getOrDefault(orderNo, 1) < 2) {
        publish("reconfirm", Map("orderNo" -> orderNo, "verdict" -> "银行系统拥堵，本次未成功 → 归入失败并自动重批"))
        ledgerAdd(s"查证 ${orderNo}：银行系统拥堵 → 归入失败（NETWORK_ERROR），进入重批")
        Failure[String](s.item.toString, net.imadz.m25.component.FailureReason(
          "NETWORK_ERROR", "银行系统拥堵", Some(NextStep.RetrySameArea(3.seconds))))
      } else {
        publish("reconfirm", Map("orderNo" -> orderNo, "verdict" -> "查证成功 → 归入成功"))
        ledgerAdd(s"查证 ${orderNo}：确认成功")
        Success[String](s.item.toString, s.item.toString)
      }
    })

  private def demoOrdersIn(raw: String): String =
    orders.map(_.orderNo).find(raw.contains).getOrElse(raw)

  private val rebatchPolicy: ReBatchPolicy = ReBatchPolicy(
    maxRetries = 2,
    actionMap = Map(
      "BALANCE_INSUFFICIENT" -> NextStep.Scrap,
      "NETWORK_ERROR" -> NextStep.RetrySameArea(3.seconds)),
    defaultCooldown = 3.seconds)

  private val router = new PolicyBasedReBatchRouter[String](rebatchPolicy)

  /** 批次收尾业务编排：可疑查证 → 失败路由（重批/放弃）→ 成功项入账。 */
  private def businessClosure(batchId: String, state: BankChainState[Any, Any], round: Int): Unit = {
    val cs = state.classifications.getOrElse(Seq.empty)
    val successes = cs.collect { case s: Success[String] => s }
    val failures = cs.collect { case f: Failure[String] => f }
    val suspicious = cs.collect { case s: Suspicious[String] => s }

    // 成功项 → M1 入账（真正的业务闭环）
    successes.foreach { s =>
      val orderNo = demoOrdersIn(s.item.toString)
      orders.find(_.orderNo == orderNo).foreach { order =>
        depositService.requestDeposit(net.imadz.common.Id.of(order.customerId), Money(order.amount, Currency.getInstance("CNY")))
          .foreach { confirmation =>
            val balanceText = confirmation.balances.headOption.map(_.amount.toString).getOrElse("?")
            synchronized {
              balances = balances + (order.customerName -> balanceText)
              itemStates = itemStates + (order.orderNo -> s"✅ 成功，已到账（余额 $balanceText）")
            }
            publish("credited", Map("orderNo" -> order.orderNo, "customer" -> order.customerName,
              "amount" -> order.amount, "balance" -> balanceText))
            ledgerAdd(s"入账 ${order.orderNo}：${order.customerName} +${order.amount} 元（余额 $balanceText）")
          }
      }
    }

    // 可疑 → 查证
    reconfirmHandler.reconfirm(suspicious).foreach { resolved =>
      val newFailures = resolved.collect { case f: Failure[String] => f }
      val newSuccesses = resolved.collect { case s: Success[String] => s }
      newSuccesses.foreach { s =>
        val orderNo = demoOrdersIn(s.item.toString)
        orders.find(_.orderNo == orderNo).foreach { order =>
          depositService.requestDeposit(net.imadz.common.Id.of(order.customerId), Money(order.amount, Currency.getInstance("CNY")))
            .foreach { confirmation =>
              val balanceText = confirmation.balances.headOption.map(_.amount.toString).getOrElse("?")
              synchronized {
                balances = balances + (order.customerName -> balanceText)
                itemStates = itemStates + (order.orderNo -> s"✅ 查证成功，已到账（余额 $balanceText）")
              }
              publish("credited", Map("orderNo" -> order.orderNo, "customer" -> order.customerName,
                "amount" -> order.amount, "balance" -> balanceText))
            }
        }
      }

      // 失败（含查证转失败）→ 路由决策
      val allFailures = failures ++ newFailures
      if (allFailures.nonEmpty) {
        router.route(allFailures, ProcessContext(chainId, round - 1, Some(batchId))).foreach { decisions =>
          decisions.foreach { d =>
            val orderNo = demoOrdersIn(d.item.toString)
            d.nextStep match {
              case NextStep.Scrap =>
                synchronized { itemStates = itemStates + (orderNo -> "❌ 失败：余额不足（按策略放弃，不重试）") }
                publish("scrapped", Map("orderNo" -> orderNo, "reason" -> d.reason))
                ledgerAdd(s"路由 ${orderNo}：余额不足 → 按策略放弃")
              case NextStep.RetrySameArea(delay) =>
                attemptCounter.put(orderNo, round + 1)
                synchronized { itemStates = itemStates + (orderNo -> s"🔁 网络类失败，${delay} 后自动重批（第 ${round + 1} 轮）") }
                publish("rebatch-scheduled", Map("orderNo" -> orderNo, "delaySeconds" -> delay.toSeconds, "round" -> (round + 1)))
                ledgerAdd(s"路由 ${orderNo}：网络类失败 → ${delay} 后重批（第 ${round + 1} 轮）")
              case NextStep.ManualIntervention(ticket) =>
                synchronized { itemStates = itemStates + (orderNo -> s"🧑‍💼 转人工工单：$ticket") }
                publish("manual", Map("orderNo" -> orderNo, "ticket" -> ticket))
              case NextStep.RouteToArea(area, _) =>
                synchronized { itemStates = itemStates + (orderNo -> s"→ 路由到 $area") }
            }
          }
          val retryItems = decisions.collect {
            case RoutingDecision(item, NextStep.RetrySameArea(_), _) => item
          }
          if (retryItems.nonEmpty) scheduleRebatch(retryItems, round + 1)
        }
      }
    }
  }

  private var rebatchRound = 1

  /** 重批守护：冷却后把重试项作为新批次再次提交（同一 ChainExecutionActor 实体）。 */
  private def scheduleRebatch(retryItems: Seq[String], nextRound: Int): Unit = {
    if (nextRound > 3) return
    rebatchRound = nextRound
    classicSystem.scheduler.scheduleOnce(3.seconds) {
      val retryOrders = orders.filter(o => retryItems.exists(i => i.contains(o.orderNo)))
      val rebatchId = s"batch-r$nextRound-${java.util.UUID.randomUUID().toString.take(8)}"
      publish("rebatch-started", Map("batchId" -> rebatchId, "round" -> nextRound,
        "orders" -> retryOrders.map(_.orderNo)))
      ledgerAdd(s"重批 $rebatchId 开始（第 $nextRound 轮，${retryOrders.size} 笔）")
      val entityRef = sharding.entityRefFor(ChainExecutionActor.EntityKey, s"$chainId-$rebatchId")
      import akka.actor.typed.scaladsl.AskPattern._
      implicit val sched = system.scheduler
      entityRef.ask(ref => ChainExecutionActor.StartExecution(rebatchId, retryOrders.toList, ref))
        .foreach { _ => () }
    }
  }

  // ====================================================================
  // Sharding wiring
  // ====================================================================

  private def itemLoader(batchId: String): Future[Seq[Any]] = {
    // 重批轮次：batch-r2-xxx / batch-r3-xxx → 只装本轮重试的单子
    if (batchId.startsWith("batch-r")) {
      val round = batchId.split("-")(1).trim.toInt
      Future.successful(orders.filter(o => attemptCounter.getOrDefault(o.orderNo, 1) >= round && bankResultOf(o.orderNo) != "BALANCE_INSUFFICIENT").toList)
    } else {
      Future.successful(orders.toList)
    }
  }

  def initSharding(): Unit = {
    val observer = new ChainExecutionActor.ChainExecutionObserver with Logging {
      override def onStart(batchId: String, itemCount: Int): Unit = {
        publish("batch-started", Map("batchId" -> batchId, "items" -> itemCount))
        ledgerAdd(s"批次 $batchId 开始（$itemCount 笔）")
      }
      override def onStageStart(cursor: String): Unit = {
        currentStage = cursor
        publish("stage-start", Map("cursor" -> cursor))
      }
      override def onStageComplete(cursor: String, metadata: Map[String, String],
                                    snapshot: Option[BankChainState[Any, Any]]): Unit = {
        doneStages = doneStages :+ cursor
        currentStage = ""
        val metaText = metadata.map { case (k, v) => s"$k=$v" }.mkString(", ")
        ledgerAdd(s"$cursor ✓  $metaText")
        // classify 完成 → 业务闭环（查证/重批/入账）
        if (cursor.startsWith("classify")) {
          snapshot.foreach { st =>
            val cs = st.classifications.getOrElse(Seq.empty)
            cs.foreach {
              case s: Success[String] =>
                val orderNo = demoOrdersIn(s.item.toString)
                synchronized { itemStates = itemStates + (orderNo -> "分类：成功（待入账）") }
              case f: Failure[String] =>
                val orderNo = demoOrdersIn(f.item.toString)
                synchronized { itemStates = itemStates + (orderNo -> s"分类：失败（${f.reason.code}）") }
              case s: Suspicious[String] =>
                val orderNo = demoOrdersIn(s.item.toString)
                synchronized { itemStates = itemStates + (orderNo -> "分类：可疑（待查证）") }
            }
            publish("classified", Map("cursor" -> cursor))
            businessClosure(batchId, st, attemptOfBatch())
          }
        }
        publish("stage-done", Map("cursor" -> cursor, "metadata" -> metadata))
      }
      override def onStageFailed(cursor: String, detail: String): Unit = {
        publish("stage-failed", Map("cursor" -> cursor, "detail" -> detail))
        ledgerAdd(s"$cursor ✗  $detail")
      }
      override def onRecovery(batchId: String, completedPhases: Int): Unit = {
        publish("recovering", Map("batchId" -> batchId, "completed" -> completedPhases))
        ledgerAdd(s"检测到宕机 → 从第 ${completedPhases + 1} 道工序断点续跑（前 $completedPhases 道不重做）")
      }
      override def onCompleted(batchId: String, snapshot: Option[BankChainState[Any, Any]]): Unit = {
        publish("batch-completed", Map("batchId" -> batchId))
        ledgerAdd(s"批次 $batchId 完成 ✅")
      }
      override def onFailed(batchId: String, phase: String, reason: String): Unit = {
        publish("batch-failed", Map("batchId" -> batchId, "phase" -> phase, "reason" -> reason))
        ledgerAdd(s"批次 $batchId 在 $phase 失败：$reason")
      }
      override def onCrash(batchId: String, reason: String): Unit = {
        publish("crash", Map("batchId" -> batchId, "reason" -> reason))
        ledgerAdd(s"⚡ 模拟宕机：$reason（账本已保存，等待重启恢复）")
      }
    }
    ChainExecutionActor.init(sharding, demoPipeline.asInstanceOf[SubBatchPipeline[Any, Any]], itemLoader, observer)
    logger.info(s"BankBatchDemoService: ChainExecutionActor sharding initialized (one entity per batch)")
  }

  private def attemptOfBatch(): Int = if (batchId.startsWith("batch-r")) batchId.split("-")(1).trim.toInt else 1

  // ====================================================================
  // Public API
  // ====================================================================

  def startBatch(): Map[String, Any] = {
    synchronized {
      def uuidOf(name: String): String = java.util.UUID.nameUUIDFromBytes(s"bank-demo-$name".getBytes).toString
      orders = Seq(
        RechargeOrder("order-1", "客户甲", uuidOf("客户甲"), 1000.0),
        RechargeOrder("order-2", "客户乙", uuidOf("客户乙"), 2500.0),
        RechargeOrder("order-3", "客户丙", uuidOf("客户丙"), 800.0),
        RechargeOrder("order-4", "客户丁", uuidOf("客户丁"), 3000.0),
        RechargeOrder("order-5", "客户戊", uuidOf("客户戊"), 1500.0))
      batchId = s"batch-1-${java.util.UUID.randomUUID().toString.take(8)}"
      currentStage = ""; doneStages = List.empty; rebatchRound = 1
      itemStates = orders.map(o => o.orderNo -> "待处理").toMap
      ledger.clear()
      ledgerAdd(s"批次 $batchId 受理（${orders.size} 笔充值请求）")
      attemptCounter.clear()
      orders.foreach(o => attemptCounter.put(o.orderNo, 1))
    }
    // 开户（幂等）：5 个客户账户
    orders.foreach(o => createCreditBalanceService.createCreditBalance(net.imadz.common.Id.of(o.customerId), Money(0.0, Currency.getInstance("CNY"))))
    val entityRef = sharding.entityRefFor(ChainExecutionActor.EntityKey, s"$chainId-$batchId")
    import akka.actor.typed.scaladsl.AskPattern._
    implicit val sched = system.scheduler
    entityRef.ask(ref => ChainExecutionActor.StartExecution(batchId, orders.toList, ref))
    Map("success" -> true, "batchId" -> batchId, "message" -> "批次已受理，六道工序开始（含 1 笔银行卡余额不足、1 笔超时重批剧本）")
  }

  def crash(): Map[String, Any] = {
    val entityRef = sharding.entityRefFor(ChainExecutionActor.EntityKey, s"$chainId-$batchId")
    entityRef ! ChainExecutionActor.StopPipeline("演示按钮触发")
    Map("success" -> true, "message" -> "宕机已注入：实体将被 sharding 重启并从断点续跑")
  }

  def stateJson: Map[String, Any] = synchronized {
    Map(
      "batchId" -> batchId,
      "currentStage" -> currentStage,
      "doneStages" -> doneStages,
      "orders" -> orders.map(o => Map("orderNo" -> o.orderNo, "customer" -> o.customerName,
        "amount" -> o.amount, "status" -> itemStates.getOrElse(o.orderNo, "待处理"))),
      "ledger" -> ledger,
      "balances" -> balances)
  }
}
