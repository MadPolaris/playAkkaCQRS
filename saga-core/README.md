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

**重要提示**：在目前的 TCC 模型中，你需要为每个阶段（Prepare, Commit, Compensate）显式注册对应的步骤。如果某个参与者在某个阶段没有注册步骤，该阶段的动作将不会被触发。

```scala
val orderPart = new OrderParticipant(...)
val stockPart = new InventoryParticipant(...)

val steps = List(
  // --- Prepare 阶段：先创单(G1)，再扣库存(G2) ---
  SagaTransactionStep("CreateOrder", PreparePhase, orderPart, stepGroup = 1),
  SagaTransactionStep("ReduceStock", PreparePhase, stockPart, stepGroup = 2),
  
  // --- Commit 阶段：确认同步 ---
  SagaTransactionStep("CreateOrder", CommitPhase, orderPart, stepGroup = 1),
  SagaTransactionStep("ReduceStock", CommitPhase, stockPart, stepGroup = 2),
  
  // --- Compensate 阶段：回滚 ---
  // 提示：即使这里 stepGroup 依然是 1 和 2，
  // 引擎在回滚时会自动计算反向顺序，先回滚 Group 2，再回滚 Group 1。
  SagaTransactionStep("CreateOrder", CompensatePhase, orderPart, stepGroup = 1),
  SagaTransactionStep("ReduceStock", CompensatePhase, stockPart, stepGroup = 2)
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

## 序列化配置 (Serialization)

由于 Saga 状态是持久化的，所有参与者和执行结果都必须支持分布式序列化。本引擎采用 **Protobuf + 策略模式** 的设计。

### 1. 实现序列化策略
你需要为你的业务参与者定义一个 `SagaParticipantSerializerStrategy`。

**参考实现：** `app/net/imadz/application/services/transactor/DynamicShowcaseParticipant.scala`
```scala
object ShowcaseStrategy extends SagaParticipantSerializerStrategy {
  override def participantClass: Class[_] = classOf[DynamicShowcaseParticipant]
  override def manifest: String = "DynamicShowcaseParticipant"
  
  override def toBinary(participant: SagaParticipant[_, _, _]): Array[Byte] = 
    participant.asInstanceOf[DynamicShowcaseParticipant].participantId.getBytes("UTF-8")
    
  override def fromBinary(bytes: Array[Byte]): SagaParticipant[_, _, _] = 
    new DynamicShowcaseParticipant(new String(bytes, "UTF-8"))
}
```

### 2. 注册策略
在基础设施层，将你的策略注册到 `SerializationExtension` 中。

**参考位置：** `app/net/imadz/infrastructure/persistence/strategies/TransactionSerializationStrategies.scala`
```scala
// 在 Bootstrap 阶段执行
SerializationExtension(system).registerStrategy(ShowcaseStrategy)
```

### 3. 结果对象的序列化
事务的返回结果（`SagaResult[R]`）默认通过 Akka 的原生序列化机制处理。建议为你的结果对象混入 `CborSerializable` 标记接口。

**参考位置：** `app/net/imadz/application/services/transactor/MoneyTransferProtocol.scala`
```scala
case class MyResult(data: String) extends CborSerializable
```

---

## 状态语义说明

*   **COMPLETED (SUCCESS)**：业务目标达成，所有参与者已 Commit。
*   **FAILED (ROLLED BACK)**：业务失败，但所有参与者已成功 Compensate，系统回到初始一致状态。
*   **FAILED (INCONSISTENT)**：事务挂起（Suspended），存在未能自动回滚的步骤，需要人工介入。

---

## 可视化交互演示 (Showcase)

项目内置了一个实时监控仪表盘，用于演示各种极端情况下的事务行为。

### 1. 运行环境准备
确保本地 Docker 容器（MongoDB & MySQL）已启动，然后运行：
```bash
sbt run
```
访问地址：`http://localhost:9000/showcase`

### 2. 核心场景演示指南

#### Case A: 混合分组编排 (Mixed Grouping)
*   **操作**：点击 `Start Transaction`。
*   **观察**：
    1.  **串行执行**：`Step A` (Group 1) 变绿后，`Step B` 和 `Step C` 才开始。
    2.  **并行执行**：`Step B` 和 `Step C` (Group 2) 同时变蓝并变绿。
*   **价值点**：展示引擎支持复杂的步骤依赖关系。

#### Case B: 自动故障重试 (Self-Healing)
*   **配置**：在 `Step-A Behavior` 下拉框选择 `Retryable Fail`。
*   **观察**：
    1.  `Step A` 会变橙色虚线（重试中）。
    2.  引擎默认在前 2 次尝试中模拟失败。
    3.  第 3 次尝试时自动成功，流程继续。
*   **价值点**：展示引擎对瞬时故障（如网络抖动）的自动容错能力。

#### Case C: 补偿阶段的反向回滚 (Reverse Rollback)
*   **配置**：设置 `Step-B` 为 `Non-Retryable Fail`。
*   **观察**：
    1.  `Step A` (G1) 成功，`Step B` (G2) 失败。
    2.  触发回滚。
    3.  **注意顺序**：`Step B` 和 `Step C` 先执行补偿，等它们完成后，`Step A` 才执行补偿。
*   **价值点**：符合“后发先回滚”的逻辑一致性。

#### Case D: 人工干预起死回生 (Manual Fix & Retry)
*   **场景**：当 `Prepare` 阶段失败且不可重试。
*   **操作**：
    1.  设置 `Step-A` 为 `Non-Retryable Fail` 并启动。
    2.  `Step-A` 变红，事务卡住。
    3.  点击 `Step-A` 下方的 `🛠️ Fix` 按钮。
    4.  点击左侧 `🔄 Retry Phase`。
*   **观察**：协调器重新评估阶段，发现 A 已成功，跳过 A 并继续执行 B/C。
*   **价值点**：展示“运维级”的数据修正能力。

#### Case E: 补偿失败后的挂起恢复 (Emergency Resolve)
*   **场景**：回滚过程中下游系统宕机。
*   **操作**：触发补偿并让补偿步骤报错。
*   **观察**：事务显示 `SUSPENDED`。点击 `Fix` 并点击 `Resume`。
*   **价值点**：展示如何解决分布式事务中最危险的“资源死锁”问题。

