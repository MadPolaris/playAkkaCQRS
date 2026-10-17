# 架构增强：Saga 与 Monarch 声明式流式 DSL 与真源树对齐规范

本分支 (`feature/enhanced-saga-monarch-dsl`) 完成了 `saga-core` 与 `monarch-core` 引擎暴露 API 的全面评估与流式 DSL 升级，旨在消除样板代码、降低开发者理解成本，并与真源树描述档（`workflows/*.saga.yaml`、`workflows/*.monarch.yaml`）实现 1:1 结构对齐。

---

## 一、 Saga DSL：`SagaDefinition` + `SagaStep.builder`

### 1. 痛点与改进
* **原先痛点**：声明一个 TCC 参与者必须手写继承自 `AskParticipant` 的样板类，并在内部写满 5 个泛型参数的 `PhaseAsk.ask[Cmd, Reply, E, R, C]`，还要手动混入 `PhaseAwareParticipant`，与真源树 YAML 的层级关系脱节。
* **流式 DSL 升级**：
  * 新增 `net.imadz.infra.saga.dsl.SagaStepBuilder`，通过 `SagaStep.builder[E, C](stepId)` 或包函数 `stepBuilder[E, C](stepId)` 启动；
  * 自动混入 `PhaseAwareParticipant`：仅展开实际绑定的阶段（未绑定的 `commit` / `compensate` 在 `expand` 时自动跳过）；
  * `prepareAsk[Cmd, Reply]` / `commitAsk[Cmd, Reply]` / `compensateAsk[Cmd, Reply]` 将类型参数从 5 个收敛为 2 个；同时支持 `prepareDirect` / `commitDirect` / `compensateDirect`（异步函数）与 `prepareBinding` / `commitBinding` / `compensateBinding`（底层 `PhaseAsk`）。

### 2. 真源树 YAML 与 Scala Saga DSL 逐字段对照

| 真源树 YAML (`*.saga.yaml`) | Scala Saga DSL (`SagaDefinition` + `SagaStep.builder`) | 说明 |
|---|---|---|
| `sagaId` / `version` | `SagaDefinition(name = "...", version = 1, ...)` | 事务定义唯一标识与版本号（用于持久化与漂移检测） |
| `argsSchema` | `argsCodec = ArgsCodec.playJson[Args]` | 参数序列化与 `argsHash` 幂等摘要计算 |
| `preChecks` | `preCheck = args => Either[E, Args]` | 协调器持久化前的同步前置校验 |
| `steps[].stepId` | `SagaStep.builder[E, C](stepId)` | 步骤唯一标识 |
| `steps[].executionGroup` | `.inGroup(stepGroup)` | 执行组号（同组并行、跨组串行、逆序补偿） |
| `steps[].resilience` | `.withResilience(ResiliencePolicy(...))` / `.withTimeout(...)` | 步骤级重试、退避、单次超时与熔断策略 |
| `steps[].errorClassification` | `.withErrorRules(ErrorRules(business = ..., describe = ...))` | 双轨错误分类（业务 `Left(E)` + 异常捕获） |
| `steps[].tccBindings.prepare` | `.prepareAsk[Cmd, Reply](ref, command, mapReply)` / `.prepareDirect(...)` | Try 阶段绑定 |
| `steps[].tccBindings.commit` | `.commitAsk[Cmd, Reply](ref, command, mapReply)` / `.commitDirect(...)` | Confirm 阶段绑定（可选，未绑定自动跳过） |
| `steps[].tccBindings.compensate` | `.compensateAsk[Cmd, Reply](ref, command, mapReply)` / `.compensateDirect(...)` | Cancel 阶段绑定（可选，未绑定自动跳过） |
| `postEvents` | `onResult = (args, result) => Seq[SagaBusinessEvent]` | 终态后由投影异步发布的业务事件 |

```scala
// 改造后示例（与 workflows/money-transfer.saga.yaml 结构 1:1 对齐，三步链式 .ref -> .command -> .mapReply 自动推导 Cmd）：
SagaStep.builder[iMadzError, MoneyTransferContext]("transfer-out")
  .inGroup(1)
  .withResilience(ResiliencePolicy(maxRetries = 5))
  .withTimeout(5.seconds)
  .withErrorRules(accountErrorRules)
  .prepare
    .ref(ctx => ctx.repository.findCreditBalanceByUserId(fromUserId))
    .command[FundsReservationConfirmation]((txId, replyTo) => ReserveFunds(Id.of(txId), money, replyTo))
    .mapReply(r => r.error.map(Left(_)).getOrElse(Right(SagaResult(r.transferId.toString))))
  .commit
    .ref(ctx => ctx.repository.findCreditBalanceByUserId(fromUserId))
    .command[FundsDeductionConfirmation]((txId, replyTo) => DeductFunds(Id.of(txId), money, replyTo))
    .mapReply(r => r.error.map(Left(_)).getOrElse(Right(SagaResult(r.transferId.toString))))
  .compensate
    .ref(ctx => ctx.repository.findCreditBalanceByUserId(fromUserId))
    .command[FundsReleaseConfirmation]((txId, replyTo) => ReleaseReservedFunds(Id.of(txId), money, replyTo))
    .mapReply(r => r.error.map(Left(_)).getOrElse(Right(SagaResult(r.transferId.toString))))
  .build()
```

---

## 二、 Monarch DSL：`Monarch.builder` + `BankChain.builder` + `ChainDsl.define`

### 1. 痛点与改进
* **原先痛点**：组装 `Monarch` 引擎需要裸传 4 个构造参数（`interpreter`, `hooks`, `failureInterceptor`, `runToken`），手写 `new LifecycleHooks` 匿名类不仅冗长，还容易因同名方法遮蔽引发意外递归（如 `override def stageName(s) = stageName(s)`）。
* **流式 DSL 升级**：
  * 新增 `net.imadz.monarch.MonarchBuilder`，通过 `Monarch.builder[Stage, State]` 启动；
  * 在 `dag-engine-core` 新增 `BankChain.builder[Item, Raw](pipeline)`，预置银行六阶段队列、阶段命名与解释器，宿主只需追加钩子与世代守卫即可 `.build()`。

### 2. 真源树 YAML 与 Scala Monarch DSL 逐字段对照

| 真源树 YAML (`*.monarch.yaml`) | 引擎层 DSL (`Monarch.builder` / `BankChain.builder`) | 业务链层 DSL (`ChainDsl.define`) |
|---|---|---|
| `stages[].cursorName` | `.namingStages(stage => String)` | 预置 `BankStage.stageName` |
| `stages`（初始阶段队列） | `.withStages(Seq[Stage])` | 预置 `BankStage.chain`（6 阶段） |
| `stages[].interpreter` | `.withExecution((stage, state) => ...)` / `.withExecutionEc(...)` | `c.fileGen` / `c.upload` / `c.waitAck` / `c.pollResp` / `c.parse` / `c.classify` |
| `lifecycleHooks.onStart` | `.onStageStart(cursor => ...)` | 宿主 Actor 可选挂载 |
| `lifecycleHooks.onComplete` | `.onStageComplete((cursor, state) => ...)` / `.onStageCompleteWithMeta(...)` | `ChainExecutionActor` 持久化 `PhaseDone(snapshot)` |
| `lifecycleHooks.onFailed` | `.onStageFailed((cursor, error) => ...)` | 宿主 Actor 记录 `ExecutionFailed` / `PhaseFailed` |
| `lifecycleHooks.onResolved` | `.onStageResolved((cursor, error, state) => ...)` | Fab 宿主记录 `OcapResolved` |
| `failurePolicy.interceptor` | `.onFailure((cursor, err, state) => ...)` / `.onFailureEc(...)` | Fab OCAP 拦截器；银行链在逐笔分类后走 `c.onFailure { r => ... }` |
| `concurrencyGuard.runToken` | `.withGenerationGuard(() => Boolean)` | `RunRegistry.register` + `RunRegistry.isFresh` |

```scala
// 改造后示例（与 workflows/fab-m35-self-healing.monarch.yaml 结构 1:1 对齐）：
val engine = Monarch.builder[PipelineStage, FabDemoState]
  .namingStages(stageName)
  .withExecutionEc { (stage, state, ec) =>
    implicit val implEc: ExecutionContext = ec
    FabScenarioPipeline.runStage(stage, state, ctx).recoverWith {
      case FabStageFailedException(err) =>
        Future.failed(MonarchStageFailedException(toMonarchError(err)))
    }
  }
  .onStageStart(cursor => if (ctx.runToken()) ctx.self ! PhaseStarting(cursor))
  .onStageCompleteWithMeta((cursor, state, meta) => if (ctx.runToken()) ctx.self ! PhaseCompleted(cursor, meta, Some(state)))
  .onStageFailed((cursor, err) => if (ctx.runToken()) ctx.self ! PhaseFailed(cursor, toFabError(err)))
  .onStageResolved((cursor, err, state) => if (ctx.runToken()) ctx.self ! OcapResolved(cursor, toFabError(err), state))
  .onFailureEc { (_, err, state, ec) =>
    implicit val implEc: ExecutionContext = ec
    FabScenarioPipeline.invokeOcapInterceptor(state, ctx, toFabError(err))
  }
  .withGenerationGuard(ctx.runToken)
  .build()
```

---

## 三、 本仓库全场景落地清单

| 模块 | 业务场景 | DSL 入口 | 所在源码文件 |
|---|---|---|---|
| **M1 银行转账 Saga** | `money-transfer`（扣款预留 + 入账登记并行 TCC） | `SagaDefinition` + `SagaStep.builder` | `app/net/imadz/application/services/transactor/MoneyTransferSagaDefinition.scala` |
| **M3 Fab 晶圆厂 Saga** | `lot-split` / `lot-merge` / `wafer-transfer` / `rework-sublot`（4 条多组串并行 TCC） | `SagaDefinition` + `SagaStep.builder` | `app/net/imadz/application/services/transactor/FabSagaDefinition.scala` |
| **M3.5 Fab 自愈流水线** | 17 阶段光刻→量测→OCAP 拦截→子批返工→断点续跑 | `Monarch.builder[PipelineStage, FabDemoState]` | `app/net/imadz/application/chain/FabPipelineProcessor.scala` |
| **M2.5+ 银行批处理引擎** | 充值 / 申购 / 设备区 6 阶段文件交换 + 崩溃快照恢复 | `BankChain.builder(pipeline)` + `ChainDsl.define` | `dag-engine-core/.../BankChain.scala`、`ChainExecutionActor.scala`、`ChainDsl.scala` |
