<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/saga/saga-serializer.md; catalog 2.5.0; source-sha256 88f0069c1cb294b7f582f3e284fefe94828da3d29f754afc95bef24d9737c28b. Edit the central document, not this snapshot. -->

# Saga序列化与转换

类别：`saga-serializer`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

把具体Saga持久类型转换为版本化bytes，保持阶段/决定/回执与恢复身份。不是所有AggregateConverter的泛用文档。

## 输入与人工信息

wire版本、serializer ID、codec pin和持久事件/状态注册来自对应runtime；应用host需独立serializer。

## 生产与所有权

- `saga-strict`消费库的StrictSagaSerializer/StrictSagaProtoConverters：serializer ID `77102231`处理StrictEvent/StrictState的`event-v2`/`state-v2`，不是每个业务生成的serializer。`saga-legacy`的SagaExecutorConverter属于另一旧协议。
- `js-formal`及`bank-p23`由`renderHostAdapters`真实生成`<Host>HostSerializer.scala`和host proto；该serializer以`742000 + ordinal`、`event-v1`处理应用`host.Event`，仅委托运行库identity转换，不生成StrictSagaSerializer。
- `bc-semantic-products`有独立ProductHostSupportTemplate链；`js-run-all-strict`生成其显式args/Money codec与host支持源码，不应把所有codec输出都称为运行库持久化serializer。
- application.conf的host.Event绑定与消费StrictDurable的库绑定分开，不能因需要库就删掉真实HostSerializer生成能力。

逐profile的源码、协议与证据级别见CAPABILITY-MATRIX.md（公共包 docs/artifacts/CAPABILITY-MATRIX.md）。P23的PB/PID/codec及冻结运行库不变。

## 结构与命名

StrictSagaSerializer/ProtoConverters与legacy SagaExecutorConverter分别消费自己的schema，StrictJacksonModule只作用于明确类型。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

运行库负责其内部格式；生成器实际生成应用HostSerializer/host proto，并配置宿主Event与库StrictDurable的独立绑定。不能将应用格式归为StrictSagaSerializer生成，也不把旧namespace当新runtime的迁移入口。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

实际序列化系统与PB往返、版本错配、未知receipt/计划digest拒绝。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

## 版本与实例

运行库持久化serializer、JS/BC生成HostSerializer与run-all业务codec不能共用一个归属结论。测试源码不等真实序列化/恢复已验证；P23冻结，最终批准未授予。

- `saga:src/main/scala/net/imadz/infra/saga/strict/StrictSagaSerializer.scala` — `StrictSagaSerializer`。
- `saga:src/main/scala/net/imadz/infra/saga/strict/StrictSagaProtoConverters.scala` — `StrictSagaProtoConverters`。
- `saga:src/main/scala/net/imadz/infra/saga/serialization/SagaExecutorConverter.scala` — `SagaExecutorConverter`（legacy）。
- `js:generators/scala-cqrs-application/render-host-adapters.mjs` — `renderHostAdapters`（应用host）。
- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/products/ProductHostSupportTemplate.scala` — `generate`（独立BC host支持）。

来源revision、摘要及处置见sources.lock.json（公共包 docs/artifacts/sources.lock.json）；不改serializer ID/manifest消除历史失败。
