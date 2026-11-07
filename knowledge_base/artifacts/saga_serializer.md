<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/saga/saga-serializer.md; catalog 1.0.0; source-sha256 26f5c7ecafecec2fe0e327719b14e95e77097933228c1105a04a3a8f597c538d. Edit the central document, not this snapshot. -->

# Saga序列化与转换

类别：`saga-serializer`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

把具体Saga持久类型转换为版本化bytes，保持阶段/决定/回执与恢复身份。不是所有AggregateConverter的泛用文档。

## 输入与人工信息

wire版本、serializer ID、codec pin和持久事件/状态注册来自对应runtime；应用host需独立serializer。

## 生产与所有权

由列出的生产者/实现提供。BC的Scala结构与JS最终写入者必须一起判断；同文件后续JS覆写时，最终台账行归最后写入者。当前银行兼容profile只按实际已有产物标识，不由旧文档推定能力。

目录状态：`implemented`；所有权：`library`。生成文件通过公共生成/更新通道维护；独立手写实现不是生成结果的可编辑副本。

## 结构与命名

StrictSagaSerializer/ProtoConverters与legacy SagaExecutorConverter分别消费自己的schema，StrictJacksonModule只作用于明确类型。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

运行库负责其内部格式，生成器只配置消费/宿主格式；不把旧namespace当新runtime的迁移入口。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

实际序列化系统与PB往返、版本错配、未知receipt/计划digest拒绝。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

## 版本与实例

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

适用：saga-legacy / saga-strict / js-formal。当前saga-core基线4352a317的strict API与银行P23消费有明确合同，Monarch R2不自动升级Saga政策或retry/backoff/deadline。

- `saga:src/main/scala/net/imadz/infra/saga/strict/StrictSagaSerializer.scala` — `StrictSagaSerializer`。
- `saga:src/main/scala/net/imadz/infra/saga/strict/StrictSagaProtoConverters.scala` — `StrictSagaProtoConverters`。
- `saga:src/main/scala/net/imadz/infra/saga/serialization/SagaExecutorConverter.scala` — `SagaExecutorConverter`。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。
