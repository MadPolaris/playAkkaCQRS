<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/saga/saga-legacy-behavior.md; catalog 1.0.0; source-sha256 d88e4580d637503003ba2335f669df3a982b155ca1c688cc217e190a979a4ab3. Edit the central document, not this snapshot. -->

# 旧Saga阶段执行行为

类别：`saga-legacy-behavior`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

执行旧StepExecutor阶段、重试/事件应用与人工恢复流程；不得作为另一strict路径已支持重试的证据。

## 输入与人工信息

旧Resilience、参与者、命令/事件合同；业务重试政策仍要由选定profile实际消费。

## 生产与所有权

由列出的生产者/实现提供。BC的Scala结构与JS最终写入者必须一起判断；同文件后续JS覆写时，最终台账行归最后写入者。当前银行兼容profile只按实际已有产物标识，不由旧文档推定能力。

目录状态：`implemented`；所有权：`library`。生成文件通过公共生成/更新通道维护；独立手写实现不是生成结果的可编辑副本。

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

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

适用：saga-legacy。旧Saga DSL/TCC与当前strict API是并列合同；不能把strict证据和恢复保证套用旧StepExecutor。

- `saga:src/main/scala/net/imadz/infra/saga/handlers/StepExecutorCommandHandler.scala` — `StepExecutorCommandHandler`。
- `saga:src/main/scala/net/imadz/infra/saga/handlers/StepExecutorEventHandler.scala` — `StepExecutorEventHandler`。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。
