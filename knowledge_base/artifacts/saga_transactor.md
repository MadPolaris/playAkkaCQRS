<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/saga/saga-transactor.md; catalog 1.0.0; source-sha256 a28a2e0489fb14e636e64241d011be7fda7e0f17be6d945ae43bbc65a95372dc. Edit the central document, not this snapshot. -->

# Saga事务门面与声明

类别：`saga-transactor`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

描述跨聚合TCC事务参与顺序和启动/查询门面，不把Saga等同于Monarch步骤队列。

## 输入与人工信息

参与者、resource、Prepare/Commit/Compensate、交易身份、输入、定义版本和业务结果需要显式声明。

## 生产与所有权

由列出的生产者/实现提供。BC的Scala结构与JS最终写入者必须一起判断；同文件后续JS覆写时，最终台账行归最后写入者。当前银行兼容profile只按实际已有产物标识，不由旧文档推定能力。

目录状态：`implemented`；所有权：`mixed`。生成文件通过公共生成/更新通道维护；独立手写实现不是生成结果的可编辑副本。

## 结构与命名

旧Transactor/createSteps与SagaDefinition；strict使用StrictSagaDefinition、PayloadCodec和StrictParticipant。JS生成<Name>Definition是具体接线而非任意业务解释器。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

门面只使用共享Saga引擎；Commit后的独立退款是另一事务，不能以abort补偿已提交付款。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

参与者计划、阶段顺序、提交/补偿边界、失败/未知的区别和独立业务oracle。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

## 版本与实例

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

适用：saga-strict / bc-strict / js-formal / bank-p23。当前saga-core基线4352a317的strict API与银行P23消费有明确合同，Monarch R2不自动升级Saga政策或retry/backoff/deadline。

- `saga:src/main/scala/net/imadz/infra/saga/dsl/SagaDefinition.scala` — `SagaDefinition`。
- `saga:src/main/scala/net/imadz/infra/saga/strict/StrictSagaDefinition.scala` — `StrictSagaDefinition`。
- `js:generators/scala-cqrs-application/render-host.mjs` — `renderDefinitions`。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。
