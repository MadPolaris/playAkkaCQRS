<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/saga/saga-participant.md; catalog 2.5.0; source-sha256 06a46651c878326189dd4903095ad2efd9ef6657f7ba60da4afc72a606297273. Edit the central document, not this snapshot. -->

# Saga参与者适配

类别：`saga-participant`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

把单个事务资源的阶段动作和证据接口连接到协调器；参与者不拥有全局事务裁决。

## 输入与人工信息

参与者ID、resource、每阶段命令/参数与幂等/证据政策需明确。

## 生产与所有权

- `saga-strict`提供StrictParticipant/PhaseBinding运行库类型；`js-formal`的Definition、`bc-strict`的StrictSagaModule及`bc-semantic-products`的应用模板在内部生成具体typed参与者/阶段接线，不要求另有独立participant类文件。
- `bank-p23`消费生成Definition并注入手写真实I/O；`js-run-all-strict`有独立旧run-all生成入口。库接口、生成接线和真实执行三层不能合并。
- `bc-single`的SagaParticipantTemplate只生成legacy AskParticipant；`bc-multi`中`saga-participant`未被`targetModuleOf`映射，实际退回无模块prefix的project root，不能称为已落glue。`play-legacy`则为手写旧适配。

逐profile的实际生产链及路由缺口见CAPABILITY-MATRIX.md（公共包 docs/artifacts/CAPABILITY-MATRIX.md）。修正归属不删除真实strict接线，也不升级P23或重试政策。

## 结构与命名

旧Participant/AskParticipant与strict StrictParticipant[A]/PhaseBinding为不同表示；保留旧adapter和新版typed port的独立说明。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

依赖聚合Repository/Protocol或外部port，返回可信执行/查询结果；超时和拒绝不自动证明未执行。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

三阶段、重复请求、ACK丢失、同ID异body、receipt身份与恢复查询。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

## 版本与实例

strict typed inline接线、legacy AskParticipant及手写I/O分别定位，不能由旧模板认领strict能力。测试源码不等三阶段/恢复验证；P23冻结与OPEN retry项不变，最终批准未授予。

- `saga:src/main/scala/net/imadz/infra/saga/strict/StrictSagaDefinition.scala` — `StrictParticipant`、`PhaseBinding`。
- `js:generators/scala-cqrs-application/render-host.mjs` — `renderDefinitions`。
- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/application/transactor/StrictSagaModuleTemplate.scala` — `generate`（strict）。
- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/products/ProductApplicationTemplate.scala` — `generate`（semantic）。
- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/application/transactor/SagaParticipantTemplate.scala` — `generate`（legacy AskParticipant）。

来源revision、摘要及处置见sources.lock.json（公共包 docs/artifacts/sources.lock.json）；multi legacy路由仍是partial。
