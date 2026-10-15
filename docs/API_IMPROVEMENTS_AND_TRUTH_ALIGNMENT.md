# 架构增强：Saga 与 Monarch 声明式流式 API 与真源树对齐规范

本分支 (`feature/enhanced-saga-monarch-dsl`) 完成了 `saga-core` 与 `monarch-core` 引擎暴露 API 的全面评估与流式 DSL 升级，旨在消除样板代码、降低开发者理解成本，并与真源树描述档（`workflows/*.saga.yaml`、`workflows/*.monarch.yaml`）实现紧密咬合。

---

## 一、 改动摘要

### 1. `saga-core`: 声明式 `SagaStepBuilder` 与 `SagaStep.builder`
* **问题**：原先声明一个 TCC 参与者必须手写一个继承自 `AskParticipant` 的样板类，并在构造函数中配置三段复杂的 `PhaseAsk.ask`，与描述档（YAML）的层级关系存在心智脱节。
* **改进**：
  * 新增 `net.imadz.infra.saga.dsl.SagaStepBuilder`；
  * `SagaStep` 提供静态入口 `SagaStep.builder[E, C](stepId)`；
  * `dsl` 包对象提供 `stepBuilder[E, C](stepId)` 便捷函数；
  * 支持行内直接声明 `prepareAsk` / `commitAsk` / `compensateAsk`，与 YAML 中的 `tccBindings` 100% 视觉对齐。

```scala
// 改造后示例（与 workflows/money-transfer.saga.yaml 结构完全一致）：
SagaStep.builder[iMadzError, AppSagaContext]("transfer-out")
  .inGroup(1)
  .prepareAsk[ReserveFunds, FundsReservationConfirmation](
    ref = ctx => ctx.creditBalances.findCreditBalanceByUserId(userId),
    cmd = (txId, replyTo) => ReserveFunds(Id.of(txId), money, replyTo),
    map = r => r.error.map(Left(_)).getOrElse(Right(SagaResult(r.transferId.toString)))
  )
  .commitAsk[DeductFunds, FundsDeductionConfirmation](
    ref = ctx => ctx.creditBalances.findCreditBalanceByUserId(userId),
    cmd = (txId, replyTo) => DeductFunds(Id.of(txId), replyTo),
    map = r => r.error.map(Left(_)).getOrElse(Right(SagaResult(r.transferId.toString)))
  )
  .compensateAsk[ReleaseReservedFunds, FundsReleaseConfirmation](
    ref = ctx => ctx.creditBalances.findCreditBalanceByUserId(userId),
    cmd = (txId, replyTo) => ReleaseReservedFunds(Id.of(txId), replyTo),
    map = r => r.error.map(Left(_)).getOrElse(Right(SagaResult(r.transferId.toString)))
  )
  .withResilience(ResiliencePolicy(maxRetries = 5))
  .build()
```

### 2. `monarch-core`: 自解释型 `MonarchBuilder` 与 `Monarch.builder`
* **问题**：原先组装 Monarch 引擎需要裸传 4 个参数（`interpreter`, `hooks`, `failureInterceptor`, `runToken`），手写 `LifecycleHooks` 匿名类时容易出现方法命名遮蔽与递规递归调用 bug（如 `override def stageName` 递归死循环）。
* **改进**：
  * 新增 `net.imadz.monarch.MonarchBuilder`；
  * `Monarch` 伴生对象提供 `Monarch.builder[Stage, State]` 工厂方法；
  * 流式链式方法清晰拆分：
    - `namingStages`: 绑定游标命名策略（对齐 YAML 的 `cursorName`）；
    - `withExecution`: 绑定阶段解释器；
    - `onFailure`: 绑定业务级失败处置器（对齐 YAML 的 OCAP 拦截器）；
    - `withGenerationGuard`: 绑定防并发双跑的 `RunRegistry` 令牌；
    - `onStageStart` / `onStageCompleteWithMeta` / `onStageFailed` / `onStageResolved`: 语义化钩子。

```scala
// 改造后示例（与 workflows/fab-m35-self-healing.monarch.yaml 结构完全一致）：
val engine = Monarch.builder[PipelineStage, FabDemoState]
  .withStages(stages)
  .namingStages(stage => FabPipelineProcessor.stageName(stage))
  .withExecution((stage, state, ec) => FabScenarioPipeline.runStage(stage, state, ctx)(ec))
  .onFailure((cursor, err, state, ec) => FabScenarioPipeline.invokeOcapInterceptor(state, ctx, toFabError(err))(ec))
  .withGenerationGuard(ctx.runToken)
  .onStageStart(cursor => onPhaseStart(cursor))
  .onStageCompleteWithMeta((cursor, state, meta) => onPhaseComplete(cursor, state))
  .onStageFailed((cursor, err) => onPhaseFailed(cursor, toFabError(err)))
  .onStageResolved((cursor, err, state) => onOcapResolved(cursor, toFabError(err), state))
  .build()
```

---

## 二、 对齐效果与价值

1. **零样板代码**：定义步骤或组装引擎不再需要写无状态的胶水子类；
2. **描述档同构**：真源树 YAML 中每一段配置在 Scala 中都能找到对应的方法调用；
3. **保持 100% 向后兼容**：保留既有所有的 `new Monarch(...)` 与 `SagaStep(...)` 构造，老代码与测试无需任何变动。
