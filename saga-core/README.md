# Saga Core

`saga-core` 是一个基于 Akka Typed 和 Event Sourcing 构建的工业级分布式事务协调引擎。它实现了 TCC（Try-Confirm-Cancel）模式，支持高并发、强一致性、以及复杂的混合编排（串行+并行）逻辑。

## 核心特性

*   **TCC 模型支持**：完整实现 Prepare, Commit, Compensate 三阶段协议。
*   **混合编排 (Execution Groups)**：通过 `stepGroup` 支持同一阶段内步骤的顺序依赖。
*   **反向回滚 (Reverse Rollback)**：自动计算补偿顺序，确保“后执行先回滚”，保障数据一致性。
*   **单步调试模式**：支持持久化的“暂停-继续”机制，适用于敏感业务的人工确认。
*   **人工干预机制**：提供 `ManualFix`（手动纠正步骤）和 `RetryPhase`（阶段级重试）能力，应对极端故障。
*   **事件溯源与分片**：状态完全持久化，支持集群水平扩展（Cluster Sharding）和崩溃自动恢复。

---

## 快速上手

### 1. 定义参与者 (Participant)

参与者是事务中的具体业务执行者。你需要继承 `SagaParticipant` 并实现其三个核心动作：

```scala
class PaymentParticipant(accountId: String, amount: Money) extends SagaParticipant[PaymentError, String, MyContext] {

  override protected def doPrepare(txId: String, ctx: MyContext, traceId: String): ParticipantEffect[PaymentError, String] = {
    // 执行本地资源预留（如冻结资金）
    Future.successful(Right(SagaResult("Funds Frozen")))
  }

  override protected def doCommit(txId: String, ctx: MyContext, traceId: String): ParticipantEffect[PaymentError, String] = {
    // 执行确认扣款
    Future.successful(Right(SagaResult("Payment Deducted")))
  }

  override protected def doCompensate(txId: String, ctx: MyContext, traceId: String): ParticipantEffect[PaymentError, String] = {
    // 执行清理工作（如解冻资金）
    Future.successful(Right(SagaResult("Funds Released")))
  }
}
```

### 2. 编排事务步骤

你可以通过 `stepGroup` 来决定步骤的执行顺序。
*   同一 `stepGroup` 内的步骤会 **并行** 执行。
*   不同 `stepGroup` 之间会按序号 **串行** 执行。

```scala
val steps = List(
  // Group 1: 必须先创建订单
  SagaTransactionStep("CreateOrder", PreparePhase, orderPart, stepGroup = 1),
  
  // Group 2: 订单创建成功后，并行扣减余额和库存
  SagaTransactionStep("DeductBalance", PreparePhase, paymentPart, stepGroup = 2),
  SagaTransactionStep("ReduceStock", PreparePhase, stockPart, stepGroup = 2)
)
```

### 3. 启动事务

通过 `SagaTransactionCoordinator` 发送 `StartTransaction` 命令。

```scala
val coordinator: EntityRef[Command] = sharding.entityRefFor(TypeKey, transactionId)

coordinator ! StartTransaction(
  transactionId = "tx-123",
  steps = steps,
  replyTo = Some(replyRef),
  traceId = "trace-uuid-abc",
  singleStep = false // 是否开启单步交互模式
)
```

---

## 运维与人工干预

当事务由于外部系统不可用导致 Compensate 失败并进入 `SUSPENDED` 状态时，管理员可以介入：

1.  **手动修复步骤 (Manual Fix)**：
    管理员线下处理数据后，通知引擎该步骤已“逻辑成功”。
    ```scala
    coordinator ! ManualFixStep(stepId = "DeductBalance", phase = CompensatePhase)
    ```

2.  **重试阶段 (Retry Phase)**：
    在修复某个步骤后，要求引擎重新评估当前阶段的状态。
    ```scala
    coordinator ! RetryCurrentPhase()
    ```

3.  **恢复执行 (Resume)**：
    唤醒挂起的协调器继续工作。
    ```scala
    coordinator ! ResolveSuspended()
    ```

## 序列化建议

所有持久化事件均使用 Protobuf 定义（参见 `saga_v2.proto`）。新增业务参与者时，需实现对应的 `SagaParticipantSerializerStrategy` 并注册到 `SerializationExtension` 中。

---

## 状态语义说明

*   **COMPLETED (SUCCESS)**：业务目标达成，所有参与者已 Commit。
*   **FAILED (ROLLED BACK)**：业务失败，但所有参与者已成功 Compensate，系统回到初始一致状态。
*   **FAILED (INCONSISTENT)**：事务挂起（Suspended），存在未能自动回滚的步骤，需要人工介入。
