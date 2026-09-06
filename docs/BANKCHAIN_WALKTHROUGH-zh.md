# 手把手：从业务问题到 Monarch 链路 —— 充值与申购全过程

本文以**银行批量充值 + 基金申购**为完整案例，从业务问题定义开始，经历问题分解建模、方案设计、映射到 Monarch DSL，最终落到可运行、可验证的代码。读完你应当能独立为自己业务的批量链路建模并接入引擎。

前置阅读：[白话教程](../monarch-core/TUTORIAL-zh.md)（六个痛点）· [ChainDsl 指南](CHAINDSL_GUIDE-zh.md)（组件与决策参考）。

---

## 目录

1. [第一阶段：问题定义](#1-第一阶段问题定义)
2. [第二阶段：问题分解与建模（五个决策）](#2-第二阶段问题分解与建模五个决策)
3. [第三阶段：方案设计——分层与关键取舍](#3-第三阶段方案设计分层与关键取舍)
4. [第四阶段：映射到代码（BankChain + ChainDsl + Actor）](#4-第四阶段映射到代码bankchain--chaindsl--actor)
5. [第五阶段：运行与验证（对账守恒）](#5-第五阶段运行与验证对账守恒)
6. [第六阶段：踩坑记录](#6-第六阶段踩坑记录)

---

## 1. 第一阶段：问题定义

### 业务场景

- **充值**：客户从银行卡把钱充进理财账户。渠道方定时把积攒的充值请求**打包成批**，发给合作银行扣款，回盘后给理财账户入账。
- **申购**：理财账户里的钱申购基金。渠道方把一批申购委托发给基金公司，确认份额后回盘逐笔通知。

### 约束（为什么不能同步调用）

外部机构（银行/基金公司）不接受同步接口——只接受**文件交换**：你给我一个请求文件，我处理完回你一个结果文件。这就决定了流程形态：

```
一批单子 → 生成文件 → 发给外部机构 → 等回执 → 取结果文件 → 解析 → 逐笔定论
```

### 必须回答的六个业务问题（痛点 → 需求）

| # | 业务问题 | 需求 |
|---|---|---|
| 1 | 跑到一半系统挂了，这批单子算做到哪了？ | 断点续跑，已完成的绝不重做 |
| 2 | 重启后"上一个我"还在跑怎么办？ | 旧任务自动退场，不能双跑 |
| 3 | 银行返回"处理超时"，钱扣没扣？ | 可疑必须查证落定，不许悬着 |
| 4 | 失败的单子要不要重试？ | 按错误码定策略：余额不足放弃、网络超时重试 |
| 5 | 每笔单子的每一步要能追溯 | 全程留痕 |
| 6 | 下个业务（代付）不想从头写 | 骨架复用，只换参数 |

---

## 2. 第二阶段：问题分解与建模（五个决策）

### 决策一：阶段 ADT——切六刀

**切法检验标准**：这一步失败了，业务上要不要单独处置？要，就值得成为一个阶段。

```scala
sealed trait BankStage
object BankStage {
  case object FileGen  extends BankStage   // 生成对账文件
  case object Upload   extends BankStage   // 传给外部机构
  case object WaitAck  extends BankStage   // 等回执
  case object Poll     extends BankStage   // 轮询结果文件
  case object Parse    extends BankStage   // 解析逐笔结果
  case object Classify extends BankStage   // 按错误码逐笔定论

  val chain: Seq[BankStage] = Seq(FileGen, Upload, WaitAck, Poll, Parse, Classify)
}
```

反例：不要把"上传 + 等回执"合成一个阶段——回执超时和文件生成失败的业务处置完全不同，混在一起就失去了分别处置的能力。

### 决策二：状态——一个 case class 穿全场

旧写法是 for 推导里六个局部变量。新建模把它们全部变成**一个状态上的 Option 槽位**：

```scala
final case class BankChainState[Item, Raw](
    batchId: String, chainId: String, items: Seq[Item],
    generatedFile:  Option[GeneratedFile] = None,   // FileGen 写入
    receipt:        Option[UploadReceipt] = None,   // Upload 写入
    ack:            Option[AckResult]     = None,   // WaitAck 写入
    responseFile:   Option[ResponseFile]  = None,   // Poll 写入
    rawResults:     Option[Seq[Raw]]      = None,   // Parse 写入
    classifications: Option[Seq[Classification[Item]]] = None,
    lastStage:      Option[BankStage]     = None
)
```

**链序约束**：每道工序的输入槽位必须由前道工序写好（Upload 读 `generatedFile`）。断点恢复时，快照把这些中间值整体带回——缺了它，第 4 道工序无从执行。

### 决策三：失败分类学——两类失败，两种命运

| 类别 | 抛出方式 | 例子 | 命运 |
|---|---|---|---|
| 已分类（业务） | `throw StageFailedException(StageError(cursor, Some("ACK_TIMEOUT"), ...))` | 回执超时、余额不足 | 失败拦截器（业务处置）|
| 未预期 | 任何 `NonFatal` 自动包装为 `UNEXPECTED` | 空指针、解析崩溃 | 同一路径，通常转人工 |

关键代码习惯——错误码就是业务语言：`ACK_TIMEOUT` / `ACK_REJECTED` / `POLL_TIMEOUT` 是回执与轮询阶段的专用码；逐笔结果的错误码（`BALANCE_INSUFFICIENT` 等）在 Classify 阶段才出现。

### 决策四：逐笔三分类——一笔单子的四种终态

第 6 道分类后，每笔单子进入终态状态机（**每笔有且只有一个终态**）：

```
在途 ──┬─→ credited / paid   成功已入账/已扣款
       ├─→ rejected          业务拒绝，按策略放弃
       ├─→ manual            重试上限，转人工工单
       └─→ except_pool       账务更新两次失败，进异常池
```

**查证不是终态**——可疑是暂时的：查证确认成功 → 转入成功；查证确认失败（网络类）→ 进重批；不确定 → 保守按失败进人工。

### 决策五：账本与世代号——跨重启的确定性

- **游标**：`file-gen#0, upload#1, ...` 每道工序的稳定坐标，逐阶段落盘
- **世代号**：每次启动/恢复 `RunRegistry.register`，旧 Future 链在下一道工序门口自动退场
- **幂等键 = 链 × 客户**：终态账本 `putIfAbsent` 只落一次

---

## 3. 第三阶段：方案设计——分层与关键取舍

### 分层

```
monarch-core（引擎，零 Akka）
    Monarch：队列驱动 + 游标恢复 + 世代守卫 + 失败拦截
dag-engine-core（组件 + 链定义）
    BankChain：六阶段 ADT + 状态 + 解释器（引擎接入适配器）
    ChainExecutionActor：EventSourcedBehavior 包装（审计/恢复/守卫）
app（业务编排）
    ChainDsl.define：业务参数化定义（错误码映射/策略/成批约束）
    BankBatchDemoService：规模调度（50 批并发 6）+ 查证 + 账务闭环
```

### 关键取舍

| 决策 | 选择 | 理由 |
|---|---|---|
| 链信息放哪 | 随 items 走（`ChainItem(c, chain, round)`） | 一套流水线服务两条链，classify 从条目自取链与轮次 |
| 重批名单怎么带入下轮 | `BatchJob` 直接携带 items；Actor 端 `jobItems` 登记表供崩溃恢复取回 | 申购批的 items = 充值成功名单（不是客户切片）——用切片会把未充值客户也拿去申购 |
| 查证成功的入账时点 | 账务全部落定后，再衔接申购链 | 否则申购扣款会与充值入账竞争，扣款时余额还没到账 |
| 世代号放哪 | 引擎（monarch-core RunRegistry） | Fab 侧先实现、证明正确后下沉，避免两处各写一份 |
| 失败处置放哪 | 业务层（ChainDsl 策略 + 查证处理器） | 引擎只保证控制流正确；"失败之后怎么办"是业务决策 |

---

## 4. 第四阶段：映射到代码

### 4.1 六道工序的解释器（`BankChain.runStage`）

```scala
case BankStage.Upload =>
  state.generatedFile.fold(Future.failed(missing("upload", "generatedFile"))) { f =>
    pipeline.upload.upload(f, state.context)
      .map(r => state.copy(receipt = Some(r), lastStage = Some(stage)))
  }
case BankStage.WaitAck =>
  state.receipt.fold(Future.failed(missing("wait-ack", "receipt"))) { r =>
    pipeline.waitAck.waitForAck(r, state.context).map {
      case AckReceived         => state.copy(ack = Some(AckReceived), lastStage = Some(stage))
      case AckTimeout(ms)      => fail("ACK_TIMEOUT", s"外部系统回执超时 ${ms}ms")
      case AckRejected(reason) => fail("ACK_REJECTED", s"外部系统拒绝: $reason")
    }
  }
```

要点：每个分支 = 读输入槽位 → 调实现 → 写输出槽位 + `lastStage`。业务失败用 `fail(...)` 抛分类异常。

### 4.2 引擎接入（一行装配）

```scala
val monarch = BankChain.monarch(pipeline,
  hooks = new LifecycleHooks[BankStage, BankChainState[Item, String]] {
    override def stageName(stage: BankStage): String = BankStage.stageName(stage)
  })  // 无拦截器：失败即终止（充值/申购的默认策略）
monarch.initialize(BankStage.chain)
monarch.process(BankChainState(batchId = ..., chainId = ..., items = ...))
```

### 4.3 DSL：业务参数化（`ChainDsl.define`）

```scala
val recharge = ChainDsl.define[RechargeItem]("recharge") { c =>
  c.fileGen(...); c.upload(...); c.waitAck(...); c.pollResp(...); c.parse(...)
  c.classify(ChainDsl.errorCodeClassifier(...,
      mapping = ErrorCodeMapping(
        successCodes    = Set("OK"),
        failureCodes    = Map("BALANCE_INSUFFICIENT" -> NextStep.Scrap),
        suspiciousCodes = Set("TIMEOUT", "NETWORK_ERROR"))))
  c.onFailure { r =>
    r.maxRetries(3)
    r.when("TIMEOUT") { NextStep.RetrySameArea(5.minutes) }
    r.otherwise       { NextStep.ManualIntervention("UNKNOWN") }
  }
  c.scheduling { s => s.minBatchSize(1); s.maxBatchSize(100); s.batchWindow(10.minutes) }
}
```

申购链：同一段骨架，只把错误码换成 `QUOTA_EXCEEDED`。**这就是噩梦六的答案**。

### 4.4 持久化包装（`ChainExecutionActor`）

```scala
// StartExecution：注册世代 + 携带 items 启动
val generation = RunRegistry.register(s"$chainId-$batchId")
runBatch(batchId, items, skip = 0, snapshot = None, runToken = ...)

// RecoveryCompleted：新世代 + 从 PhaseDone 快照断点续跑
val generation = RunRegistry.register(key)                      // 旧链下一边界退场
itemLoader(state.batchId).onComplete { items =>
  runBatch(batchId, items, skip = state.completedPhases.size,
    snapshot = state.lastState, runToken = ...)
}
```

`PhaseDone` 携带 **snapshot**（阶段后置状态）：断点续跑时，第 4 道工序需要的 `receipt` 等中间值由快照带回。

### 4.5 重批闭环（route → 冷却 → 下一轮）

```scala
// businessClosure 内：查证转失败 + 直接失败合并后统一路由
routers(chain).route(allFailures, ctx).map { decisions =>
  var needRebatch = false
  var minDelay = policy.defaultCooldown
  var retryItems = Vector.empty[ChainItem]
  decisions.foreach { d =>
    d.nextStep match {
      case NextStep.RetrySameArea(delay) =>
        needRebatch = true
        minDelay = minDelay min delay
      case NextStep.ManualIntervention(ticket) =>
        setTerminal(chain, c, "manual"); inc(s"${chain}_manual")
      case NextStep.Scrap => ...
    }
  }
  // 整批只入队一次下一轮——逐笔入队会产生成百上千个重复批 → 调度死锁
  if (needRebatch) {
    val retryItems = decisions.collect {
      case d if d.nextStep.isInstanceOf[NextStep.RetrySameArea] =>
        ChainItem(customerById(d.item.toString), chain, round + 1)
    }.toVector
    classicSystem.scheduler.scheduleOnce(minDelay)(enqueue(BatchJob(chain, round + 1, index, retryItems)))
  }
}
```

### 4.6 终态账本（对账守恒）

```scala
// 每笔单子（链 × 客户）有且只有一个终态；幂等键 = customerId
setTerminal(chain, c, "credited")    // 账务成功后落终态
// 重复调用/重跑：putIfAbsent 只落一次，账务更新前先查 isTerminal 直接跳过
```

运行结束的守恒式：`进入客户数 = 入账成功 + 放弃 + 转人工 + 异常池 + 在途(=0)`。

---

## 5. 第五阶段：运行与验证（对账守恒）

10 万客户实跑结果（`/bank-batch` 页面实时可见）：

| 链 | 进入客户 | 入账/扣款成功 | 放弃 | 转人工 | 异常池 | 在途 | 对账 |
|---|---|---|---|---|---|---|---|
| 充值 | 100,000 | 96,243 | 1,947 | 809 | 1,001 | 0 | ✓ 平 |
| 申购 | 96,311 | 92,630 | 2,020 | 755 | 906 | 0 | ✓ 平 |

（申购进入数 = 充值链入账成功的客户数——没充值成功的客户不进入申购，这是两链衔接的业务规则。）

运行中途可随时注入宕机：账本已保存 → 实体重启 → `resumeFromIndex` 从断点续跑 → 恢复次数 +1 → 最终依然守恒。

---

## 6. 第六阶段：踩坑记录（规模才暴露的三个真 bug）

| # | 症状 | 根因 | 教训 |
|---|---|---|---|
| 1 | 重批任务暴涨至千级、队列死锁 | 路由按**失败笔数**逐笔入队下一轮 | 重批必须**整批一次**入队（去重） |
| 2 | 并发槽全占满、调度停摆 | 被拒任务不释放并发槽 | **被拒也必须释放槽位**（finally 语义） |
| 3 | 二次启动全被拒、申购进入数=全量 | 实体名确定性 + journal 跨运行串台；申购批误用全量切片 | **runId 进实体键**；申购批 items = 充值成功名单，不是客户切片 |

这三个坑和教程开篇的六个业务痛点一一对应——**引擎调试中踩的坑，就是银行业务的痛点**。这正是本案例最有教学价值的地方。

---

*完整代码：`dag-engine-core/src/main/scala/net/imadz/m25/component/BankChain.scala`、`ChainExecutionActor.scala`；测试：同目录 test 下 `BankChainSpec` / `ChainExecutionActorSpec`；运行页面：`/bank-batch`。*
