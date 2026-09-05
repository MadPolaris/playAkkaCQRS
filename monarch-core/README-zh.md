# monarch-core

[English](README.md) | 中文

> **新手入口**：先读[白话教程《一次基金申购批的奇幻漂流》](TUTORIAL-zh.md)——用一笔具体的基金申购批讲清楚断点续跑、失败重批、世代守卫，读完再回来查参考手册。

**Monarch（帝王斑蝶）** —— 可断点续跑的阶段队列执行引擎，以帝王斑蝶命名：

| 斑蝶 | 引擎 |
|---|---|
| 完全变态发育：卵 → 幼虫 → 蛹 → 成虫 | 一次运行 = **开放阶段队列**（`initialize` / `injectHead` / `appendTail`） |
| **滞育**——发育在检查点暂停数月，之后从暂停处精确续跑 | **游标恢复**（`resumeFromIndex` / `resume`）——崩溃、回放日志、从游标继续 |
| 迁徙**跨代完成**——路线比任何个体长寿 | **世代号**（`RunRegistry`）——被取代的旧链在下一个阶段边界静默终止，新链从日志续跑 |

零框架依赖：全部是 `scala.concurrent.Future` + 宿主回调，同一引擎可跑在 Akka `EventSourcedBehavior` 包装、Play Controller 或纯测试里。以 `net.imadz:monarch-core` 发布到 Maven Central（见[发布](#6-发布maven-central)）。

[English](README.md) | 中文

其他指南：[ChainDsl 指南](../docs/CHAINDSL_GUIDE-zh.md)——下面两个宿主在库层面的文档。

---

## 1. 引擎契约

Monarch 不含任何策略：它驱动一个**不透明的 `Stage` 队列**，经过**你提供的解释器**执行，并通过**钩子**回报进度。宿主要提供四样东西：

```scala
import net.imadz.monarch._

val engine = new Monarch[MyStage, MyState](
  // 1. 唯一必须编写的宿主代码：如何执行一个阶段。
  interpreter = new StageInterpreter[MyStage, MyState] {
    def run(stage: MyStage, state: MyState)(implicit ec: ExecutionContext): Future[MyState] = ...
  },
  // 2. 命名 + 观察。stageName 定义了恢复跳转逻辑所依赖的【游标词汇表】；
  //    生命周期回调是宿主写日志/发事件的地方。
  hooks = new LifecycleHooks[MyStage, MyState] {
    def stageName(stage: MyStage): String = ...
    override def onStageStart(cursor: String): Unit = ...
    override def onStageComplete(cursor: String, state: MyState, metadata: Map[String, String]): Unit = ...
    override def onStageFailed(cursor: String, error: StageError): Unit = ...
    override def onStageResolved(cursor: String, error: StageError, state: MyState): Unit = ...
  },
  // 3. 可选的业务失败处置（Fab 演示里就是 OCAP 评估/处置）。
  failureInterceptor = None,
  // 4. 世代过期守卫（由 RunRegistry 支撑）。
  runToken = () => RunRegistry.isFresh(runKey, myGeneration)
)

engine.initialize(Seq(stageA, stageB, stageC))  // 装载队列
engine.process(initialState)                     // Future[MyState]
engine.injectHead(Seq(emergencyStage))           // 运行时织入
engine.resumeFromIndex(replayedState, done = 9)  // 崩溃恢复
```

白拿的机制：

| 机制 | 说明 |
|---|---|
| **游标** | 每个队列条目获得 `"<阶段名>#<位置>"` ——稳定、唯一、可读。恢复按数量（`resumeFromIndex`）或按游标名（`resume`）跳过。 |
| **守卫优先的过期判定** | 每个阶段边界最先重查 `runToken()`。被取代的旧链以 `StaleRun` 静默失败，绝不触碰钩子或拦截器。 |
| **已分类失败** | 阶段体抛 `StageFailedException(StageError(...))` 表示*已分类*的业务失败；其他 `NonFatal` 自动包装为 `UNEXPECTED`。两者都交给 `failureInterceptor`；未配置则整个运行失败。失败**只在失败阶段的帧上处理一次**——绝不会被外层队列帧重复处理。 |
| **开放队列** | `injectHead` / `appendTail` 向运行中的计划织入阶段（OCAP 分支、返工循环）。 |

被处置的失败**不是**完成：拦截器返回恢复状态后，队列带着剩余阶段继续，但只触发 `onStageResolved`——被处置的失败对下游意味着什么，由宿主决定。

---

## 2. 建模：从业务流程到 Monarch 宿主

五个决策，把任何"把一批物品送进外部系统"的流程变成 Monarch 宿主。本仓库的两个真实宿主——`BankChain`（充值/申购，dag-engine-core）与 Fab 流水线（app 层，M3.5 演示）——走的都是这条路。

### 第 1 步 — 阶段 ADT：一个有意义的步骤一个 case

一个阶段值得独立存在，当且仅当以下任一成立：人能看到它开始/结束（UI、工单）；日志必须逐个记录（恢复边界、审计）；或它有独立的失败策略。

- 银行链：`FileGen, Upload, WaitAck, Poll, Parse, Classify` —— 六个文件交换步骤。
- Fab：十七个——`LoadFoup, Transport(from, to), AtEquipment(area, equipId), TrackIn, RunRecipe, Measure, M35ClassifyWithOcap(rules), OcapActionRouter, AwaitSubLotResult(lotKey), ...`——带参数的 case，因为同一变体会以不同参数反复出现。

```scala
sealed trait PipelineStage
case object LoadFoup extends PipelineStage
case class Transport(from: String, to: String) extends PipelineStage
case class Measure(equipId: String) extends PipelineStage
case class M35ClassifyWithOcap(rules: List[OcapRuleDefinition]) extends PipelineStage
```

经验法则：如果说不出一个步骤的**失败**意味着什么，它还不是一个阶段——只是某个阶段内部的实现细节。

### 第 2 步 — 状态：一个 case class，Option 槽位

Monarch 在队列中穿递**一个 `State` 值**。把流水线的异构中间值折叠进一个 case class，每个槽位在自己的阶段写入前都是 `Option`：

```scala
// BankChain：旧 for 推导里的 GeneratedFile、UploadReceipt、AckResult、
// ResponseFile、Seq[RawResult] 都是局部变量——现在全是槽位：
final case class BankChainState[Item, Raw](
    batchId: String, chainId: String, items: Seq[Item],
    context: Map[String, Any] = Map.empty,
    generatedFile: Option[GeneratedFile] = None,
    receipt: Option[UploadReceipt] = None,
    ack: Option[AckResult] = None,
    responseFile: Option[ResponseFile] = None,
    rawResults: Option[Seq[Raw]] = None,
    classifications: Option[Seq[Classification[Item]]] = None,
    lastStage: Option[BankStage] = None          // ← 用于 metadata 推导
)
```

链序规则：**阶段运行时其输入槽位必须已就位**（FileGen 写 `generatedFile`，Upload 读它……）。这正是链中段恢复成为可能的前提——见第 5 步。

Fab 宿主已有活的领域模型（`FabDemoState`：晶圆、lot 位置、OCAP 动作）——直接拿来当 State。如果你的流程已有领域模型，**那个模型就是 State**，不要另造一套平行的。

### 第 3 步 — 游标词汇表：稳定、可读、进日志

`stageName` 是运行系统与日志之间的契约。三条规则：

1. **人可读**——它出现在日志、UI 和故障报告里（`"RunRecipe_LITHO-01_LITHO-28-001#4"` 一眼全部信息）。
2. **跨重启稳定**——由阶段 case + 参数推导，绝不掺随机 id。
3. **迁移时保留旧字符串**——BankChain 逐字节沿用旧处理器的 `"file-gen" / "upload" / "wait-ack" / ...`，已入日志的事件回放不受影响。

### 第 4 步 — 失败分类与拦截器

 upfront 把失败分成两类：

| 类别 | 抛出方式 | 谁处理 |
|---|---|---|
| **已分类**（业务） | `throw StageFailedException(StageError(cursor, Some("ACK_TIMEOUT"), "ACK_TIMEOUT", "..."))` | 配置了 `failureInterceptor` 则交给它，否则整个运行失败 |
| **未预期**（缺陷/基础设施） | 任意 `NonFatal`——自动包装为 `StageError(cursor, None, "UNEXPECTED", ...)` | 同一路径；好的拦截器通常转人工 |

只有当业务对失败阶段存在**处置策略**时才配置拦截器。Fab 演示的 OCAP 是标准范例：对失败评估规则，返工/报废/挂起晶圆，返回队列继续的状态。银行链**不带**拦截器——它们的失败本来就该终止运行。

### 第 5 步 — 宿主集成：journal、续跑、世代号

Monarch 是 Future 队列——自己什么都不持久化。宿主用事件溯源 Actor 包住它，接三件事：

1. **把钩子写进日志。** 将 `onStageStart/Complete/Failed/Resolved` 映射到你的事件协议。完成事件要携带后置状态——恢复需要中间值。
2. **从快照续跑。** 恢复时用最后一条完成事件的快照重建 State，调用 `resumeFromIndex(state, completedCount)`。按数量跳过不需要游标匹配；日志里存的是游标时可用按名跳过的 `resume(Set(cursors))`。
3. **用世代号守卫。** 在**启动和恢复两处**都 `RunRegistry.register(key)`；把令牌捕获进 `runToken`；每个钩子发消息给 self 前都重查一次。这杀死了"崩溃前的旧 Future 链把事件落到重启实体上"的双管线竞争。

---

## 3. 案例一 — `BankChain`：充值链路（dag-engine-core）

**改造前**——六个异构中间值硬编码在 for 推导里：

```scala
// SubBatchProcessor.process——顺序、数量、形状全部冻结
for {
  generatedFile   <- pipeline.fileGen.generate(items, ctx)
  receipt         <- pipeline.upload.upload(generatedFile, ctx)
  ack             <- pipeline.waitAck.waitForAck(receipt, ctx)
  pollResult      <- pipeline.pollResp.poll(ctx)
  rawResults      <- pipeline.parse.parse(responseFile, ctx)
  classifications <- pipeline.classify.classify(rawResults, items)
} yield SubBatchResult(...)
```

**改造后**——阶段 ADT + 状态（第 1–2 步），加一个约 40 行的解释器：

```scala
def runStage(stage: BankStage, state: BankChainState[Item, Raw], pipeline: SubBatchPipeline[Item, Raw])
            (implicit ec: ExecutionContext): Future[BankChainState[Item, Raw]] = stage match {
  case BankStage.FileGen =>
    pipeline.fileGen.generate(state.items, state.context)
      .map(f => state.copy(generatedFile = Some(f), lastStage = Some(stage)))
  case BankStage.WaitAck =>
    state.receipt.fold(Future.failed(missing("wait-ack", "receipt"))) { r =>
      pipeline.waitAck.waitForAck(r, state.context).map {
        case AckReceived         => state.copy(ack = Some(AckReceived), lastStage = Some(stage))
        case AckTimeout(ms)      => fail("ACK_TIMEOUT", s"External system ack timeout after ${ms}ms")
        case AckRejected(reason) => fail("ACK_REJECTED", s"External system rejected: $reason")
      }
    }
  // Poll / Parse / Classify 同构
}
```

**包装层**（`ChainExecutionActor`，事件溯源）——第 5 步模式的完整落地：

```scala
// StartExecution——注册世代，从阶段 0 开始
val generation = RunRegistry.register(s"$chainId-$batchId")
runBatch(batchId, items, skip = 0, snapshot = None, runToken = ...)

// RecoveryCompleted——新世代，从日志快照断点续跑
val generation = RunRegistry.register(key)                     // 旧链在下一边界终止
itemLoader(state.batchId).onComplete { items =>
  runBatch(batchId, items, skip = state.completedPhases.size,  // 回放得到的游标计数
    snapshot = state.lastState, runToken = ...)                // PhaseDone 携带快照
}
```

`PhaseDone(phase, ts, metadata, snapshot)` 逐阶段存储后置状态；`BankChain.metadataOf(state)` 推导出与旧处理器相同的 metadata 键（`localPath/fileName/byteSize/...`），日志依旧人可审计。验收测试：`dag-engine-core/src/test/.../BankChainSpec.scala`。

---

## 4. 案例二 — Fab 流水线（M3.5 演示，app 层）

Fab 流程更复杂：十七个阶段变体（含 OCAP 评估与返工子 lot saga）、既有的领域状态、以及业务级失败处置策略。Monarch 仍然只需要那四个扩展点——所有 Fab 特有的东西都住在一个适配器里。

**阶段 ADT 与状态**（第 1–2 步）：`FabScenarioPipeline.PipelineStage`（十七个 case）与 `FabDemoState`——领域早已建模好，没有发明任何新类型。

**适配器**（`app/.../FabPipelineProcessor.scala`——整个文件就是一个适配器）：

```scala
new Monarch[PipelineStage, FabDemoState](
  interpreter = stage =>
    FabScenarioPipeline.runStage(stage, state, ctx).recoverWith {
      // 把 FAB 的失败类型翻译成引擎的，保留业务分类：
      case FabStageFailedException(err) =>
        Future.failed(MonarchStageFailedException(
          MonarchStageError(err.stageName, err.equipId, err.errorCode, err.detail)))
    },
  hooks = new LifecycleHooks[PipelineStage, FabDemoState] {
    def stageName(stage: PipelineStage): String = FabPipelineProcessor.stageName(stage)
    // journal 回调 → actor 命令 → 入日志的事件
    override def onStageStart(cursor: String) = if (runToken()) ctx.self ! PhaseStarting(cursor)
    override def onStageComplete(cursor: String, state: FabDemoState, _) =
      if (runToken()) ctx.self ! PhaseCompleted(cursor, Map.empty, Some(state))
    override def onStageFailed(cursor: String, error: StageError) =
      if (runToken()) ctx.self ! PhaseFailed(cursor, toFabError(error))
    override def onStageResolved(cursor: String, error: StageError, state: FabDemoState) =
      if (runToken()) ctx.self ! OcapResolved(cursor, toFabError(error), state)
  },
  failureInterceptor = Some((cursor, error, state) =>
    FabScenarioPipeline.invokeOcapInterceptor(state, ctx, toFabError(error))),  // ← OCAP 住在这里
  runToken = ctx.runToken)
```

两个只属于适配器的关注点值得注意：**异常翻译**（阶段体抛的是 Fab 的失败类型；引擎必须看到自己的类型，否则已分类的失败会退化成 `UNEXPECTED`）和**四条 journal 协议**（`PhaseStarting/PhaseCompleted/PhaseFailed/OcapResolved`）——迁移过程中日志 schema 和 WebSocket UI 完全没动。

**生产日志里的崩溃恢复**——崩溃注入在 `Measure_CDSEM-01#9` 内部，sharding 杀掉 actor，退避后重启：

```
20:48:36  >>> STAGE START: Measure_CDSEM-01#9
20:48:38  Crash injected, stopping actor
20:48:48  >>> STAGE START: Measure_CDSEM-01#9   ← 同一游标，来自 resumeFromIndex
20:48:53  <<< STAGE DONE: Measure_CDSEM-01#9
          ... TrackOut#10 → Classify#11 → OCAP#12/#13 → AwaitSubLotResult_rwk#14（返工 saga）
20:49:14  <<< STAGE DONE: SealComplete#16       → AllCompleted
```

**引擎刻意不做的事**：日志持久化、saga 协调、设备模拟器、WebSocket 发布、OCAP 规则——全是宿主的职责。Monarch 只保证**控制流**正确：阶段顺序正确、恢复点正确、每个失败恰有一个处理器、没有僵尸链。

---

## 5. 决策指南

| 问题 | 答案 |
|---|---|
| 固定六步还是开放队列？ | 只要步骤列表可能在运行时变化（OCAP 注入、返工循环），就需要队列。两个宿主目前都用固定 `initialize`；队列 API 是为将来留的。 |
| 拦截器还是快速失败？ | 除非存在**业务级**的失败后续处置策略，否则快速失败。拦截器自身抛错也会让运行失败——处置逻辑必须幂等且完备。 |
| `resumeFromIndex` 还是 `resume`？ | 完成事件携带状态快照时用按数量（`resumeFromIndex`）——最简单，且与游标格式无关。日志存的是游标而没存状态时用按名（`resume`）。 |
| 世代号从哪来？ | **每次**新运行和恢复都 `RunRegistry.register`；令牌捕获进 `runToken`，并在钩子发送时重查。注册表是 JVM 内的；跨节点漂移需要集群级信号。 |
| 宿主怎么测试？ | Monarch 是纯 Future 队列：桩掉解释器、把钩子记进 `ListBuffer`、断言精确的事件序列——见 `monarchCore/src/test/.../MonarchEngineSpec.scala`（15 个）与 `dag-engine-core/.../BankChainSpec.scala`（6 个）。 |

---

## 6. 发布（Maven Central）

仅 `monarch-core` 发布（`net.imadz:monarch-core`），其余模块均设 `publish/skip`。发布由标签驱动：推送 `v*` 标签后，既有的 `publish.yml` workflow 执行 `sbt ci-release` 签名并上传到 Central Portal（`central.sonatype.com`）。版本号由标签经 sbt-dynver 推导（如 tag `v0.1.0` → `0.1.0`）。需配置的 GitHub secrets：`OSSRH_USERNAME` / `OSSRH_PASSWORD`（Central Portal token）、`PGP_SECRET`、`PGP_PASSPHRASE`。
