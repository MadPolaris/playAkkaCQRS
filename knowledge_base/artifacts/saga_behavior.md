<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/saga/saga-legacy-behavior.md; catalog 2.1.0; source-sha256 001e54ad3db7f30005e5b5ce74384dc4f6bb6ffa880cbdc24be655abcb0a6aa7. Edit the central document, not this snapshot. -->

# 旧Saga阶段执行行为

类别：`saga-legacy-behavior`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

执行旧StepExecutor阶段、重试/事件应用与人工恢复流程；不得作为另一strict路径已支持重试的证据。

## 输入与人工信息

旧Resilience、参与者、命令/事件合同；业务重试政策仍要由选定profile实际消费。

## 生产与所有权

- `saga-legacy`消费共享StepExecutor command/event handlers，不生成每个业务的独立重试/补偿内核。BC scaffold中保留的runtime源码拷贝也不是按业务生成状态机。
- `play-legacy`的FabSagaTransactorBehaviors是手写应用门面，与库handlers不同。
- `js-legacy`的`emitLegacySagaSpec`确实从显式完整Scala spec生成Args/Context及SagaBuilder阶段/参与者声明；这是legacy应用声明源码，不是静态合同报告，也不是重新生成StepExecutor。

逐profile的库消费、手写实现和生成声明见CAPABILITY-MATRIX.md（公共包 docs/artifacts/CAPABILITY-MATRIX.md）。旧重试代码不关闭strict/P23业务retry/backoff/deadline的OPEN项。

## 结构与命名

运行库handlers为共享机制，不是每个银行流程的独立generated行为副本。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

只供legacy coordinator/runner使用，与StrictSagaCoordinator不同类。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

旧重试/退避/状态/补偿用例，特别是停止和结果未知；不把技术重传混成业务新交易。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

## 版本与实例

legacy库handlers、Play手写门面与JS生成声明分开。测试源码不等执行证据；旧恢复/重试不向strict或P23传播，最终批准未授予。

- `saga:src/main/scala/net/imadz/infra/saga/handlers/StepExecutorCommandHandler.scala` — `StepExecutorCommandHandler`。
- `saga:src/main/scala/net/imadz/infra/saga/handlers/StepExecutorEventHandler.scala` — `StepExecutorEventHandler`。
- `play:app/net/imadz/application/services/transactor/behaviors/FabSagaTransactorBehaviors.scala` — `FabSagaTransactorBehaviors`。
- `js:generators/saga-dsl/emit.mjs` — `emitLegacySagaSpec`。

来源revision、摘要及处置见sources.lock.json（公共包 docs/artifacts/sources.lock.json）；Scala声明不是静态JSON报告。
