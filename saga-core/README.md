> 📖 完整指南：[docs/SAGA_GUIDE-zh.md](../docs/SAGA_GUIDE-zh.md)（中文） / [docs/SAGA_GUIDE.md](../docs/SAGA_GUIDE.md)（EN）
> 本文件是该模块的中文速查表。

# Saga Core

`saga-core` 是一个基于 Akka Typed 和 Event Sourcing 构建的工业级分布式事务协调引擎。它实现了 TCC（Try-Confirm-Cancel）模式，支持高并发、强一致性、以及复杂的混合编排（串行+并行）逻辑。

## 核心特性

*   **TCC 模型支持**：完整实现 Prepare, Commit, Compensate 三阶段协议。
*   **声明式 DSL（v3）**：业务开发者只写一个 `SagaDefinition`（~40-60 行），不再需要 Transactor 影子聚合、步骤编排和参与者序列化。
*   **参与者零序列化**：journal 只记录 `(definition name + version, args, argsHash, StepDescriptor[])`；参与者是 `(definition, args)` 的纯函数，恢复时从注册表确定性重建。
*   **混合编排 (Execution Groups)**：通过 `stepGroup` 支持同一阶段内步骤的并行执行、组间串行。
*   **反向回滚 (Reverse Rollback)**：自动计算补偿顺序，确保"后执行先回滚"。
*   **单步调试模式**：持久化的"暂停-继续"机制，`runner.admin.proceed` 逐组推进。
*   **人工干预机制**：`ManualFix`、`RetryPhase`、`ResolveSuspended` 运维能力。
*   **崩溃恢复**：Coordinator 与 StepExecutor 均为事件溯源聚合，世代号（attempt generation）关闭迟到响应窗口。
*   **定义漂移防护**：结构漂移（stepId/phase/group 集合变化）→ 事务挂起；可调参数漂移（重试/超时）→ 告警沿用。
*   **事件溯源与分片**：状态完全持久化（saga_v3 proto journal），支持集群水平扩展。

## 快速上手（v3 DSL）

### 1. 定义参与者 (AskParticipant)

参与者按阶段声明 ask 绑定，业务错误（`Left(E)`）在 ask 边界仍带类型时分类：

```scala
case class FromAccountParticipant(fromUserId: Id, amount: Money)(implicit ec: ExecutionContext, scheduler: Scheduler)
    extends AskParticipant[iMadzError, String, MoneyTransferContext](
      rules = ErrorRules(
        business = { case iMadzError("60003", _) => ErrorAction.NonRetryable },
        describe = e => s"${e.code}: ${e.message}"),
      askTimeout = 5.seconds) {

  override protected val prepareBinding: Option[PhaseAsk[iMadzError, String, MoneyTransferContext]] =
    Some(PhaseAsk.ask[ReserveFunds, FundsReservationConfirmation, iMadzError, String, MoneyTransferContext](
      ref = ctx => ctx.repository.findCreditBalanceByUserId(fromUserId),
      command = (txId, replyTo) => ReserveFunds(Id.of(txId), amount, replyTo),
      mapReply = r => r.error.map(Left(_)).getOrElse(Right(SagaResult(r.transferId.toString)))))
  // commitBinding / compensateBinding 同理；未绑定的阶段自动跳过
}
```

*   **双轨错误分类**：`business` 规则分类 `Left(E)`（未匹配默认 NonRetryable）；`thrown` 规则叠加在默认矩阵之上（Timeout/AskTimeout/Connect/SQLTransient → Retryable，其余 → NonRetryable）。
*   未声明 `PhaseAwareParticipant` 的参与者默认展开为三个阶段各一个引擎步骤。

### 2. 定义事务 (SagaDefinition)

`steps` 必须是 `(definition, args)` 的纯函数——这正是 journal 无需存参与者的原因：

```scala
object MoneyTransferSagaDefinition {
  final case class TransferArgs(fromUserId: String, toUserId: String, amount: BigDecimal, currency: String)
  object TransferArgs { implicit val format: Format[TransferArgs] = Json.format[TransferArgs] }

  def definition(implicit ec: ExecutionContext, scheduler: Scheduler) =
    SagaDefinition[iMadzError, MoneyTransferContext, TransferArgs](
      name = "money-transfer", version = 1,
      argsCodec = ArgsCodec.playJson[TransferArgs],
      steps = args => Seq(
        SagaStep("transfer-out", FromAccountParticipant(Id.of(args.fromUserId), money), ResiliencePolicy(maxRetries = 5), stepGroup = 1),
        SagaStep("transfer-in",  ToAccountParticipant(Id.of(args.toUserId), money),  ResiliencePolicy(maxRetries = 5), stepGroup = 1)),
      preCheck = args => if (args.amount > 0) Right(args) else Left(iMadzError("40001", "amount must be positive")),
      onResult = (args, result) => List(MoneyTransferCompleted(result.snapshot.transactionId, ...)))
}
```

*   `preCheck` 在 coordinator 侧、persist 前执行，拒绝不落任何事件。
*   `onResult` 产生的 `SagaBusinessEvent` 由业务事件投影消费（at-least-once，sink 按 txId 去重）。

### 3. 引导与启动 (Bootstrap)

节点启动时**先注册定义、再初始化分片**（恢复中的在途事务按持久化的 version 解析定义；覆盖注册是部署修复后挂起事务的自愈路径）：

```scala
MoneyTransferSagaDefinition.register      // 每节点一次
initSagaEngine[MoneyTransferContext](sharding, MoneyTransferContext(repository), system)
```

journal pid 方案（getHistory 依赖）：
*   coordinator: `saga-coordinator-$txId`
*   executor:    `saga-executor-$txId-$stepId-$phase`

### 4. 启动事务 (SagaRunner)

```scala
val runner = MoneyTransferSagaDefinition.runner(system, txId => sharding.entityRefFor(SagaTransactionCoordinator.entityTypeKey, txId))

// txId 是调用方幂等键：同 txId + 同 args 重发 → AlreadyRunning / AlreadyFinished；
// 同 txId + 不同 args → ConflictingArgs 拒绝
val terminal: Future[TransactionResult] = runner.run(txId, TransferArgs(...), traceId = "", singleStep = false)

// 持久状态轮询（跨实体重启/节点崩溃）
val snapshot: Future[Option[StatusSnapshot]] = runner.statusOf(txId)
```

`run` 的 Future 由事务终态 TransactionResult 完成（经节点本地完成桥），启动拒绝/兜底超时使其失败。需要持久状态的调用方请轮询 `statusOf`。

## 运维与人工干预

当事务由于外部系统不可用导致 Compensate 失败并进入 `SUSPENDED` 状态时，管理员可通过 `runner.admin` 介入：

```scala
runner.admin.fixStep(txId, "transfer-out", CompensatePhase) // 线下处理后标记该步骤逻辑成功
runner.admin.resolveSuspended(txId)                         // 重新驱动当前阶段
runner.admin.retryPhase(txId)                               // 阶段级重试
runner.admin.proceed(txId)                                  // 单步模式逐组推进
```

## 序列化 (saga_v3)

*   **journal 事件**：proto（`saga_v3.proto`）——TransactionStarted 携带 definitionRef + args + argsHash + StepDescriptor 列表，**不含任何参与者载荷**；跨节点消息（StartSaga/SagaStartReply/TransactionResult/StatusSnapshot）为 CborSerializable 走 jackson-cbor。
*   **业务结果字节**（OperationSucceeded/ManualFixCompleted 的 result）对非 CborSerializable 类型依赖 `allow-java-serialization = on`（已知技术债，见 reference.conf 注释）。
*   快照：继续 java serializer（descriptor 化后 State 全为静态数据）。
*   args 编码必须稳定（argsHash 是幂等判别键）：升级编码格式请 bump definition version，并在途事务按旧 version 继续解析。

## 验收与测试

```bash
sbt sagaCore/test     # 全部单元 + 验收用例（AC-1.1 .. AC-1.12）
sbt acceptance        # 门禁别名（当前 G0.1+G1；G0.2/G0.3 随 Phase B/C 接入）
```

验收包位于 `net.imadz.infra.saga.acceptance`，覆盖：expand 展开正确性、双轨分类、StartSaga 幂等矩阵 7 分支、崩溃恢复（journal-PO 断言）、Attach 三分支、世代号、重入安全、定义漂移、序列化绑定、journal 内容、弹性策略激活、SagaRunner 完成桥。
