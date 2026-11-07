<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/saga/saga-participant.md; catalog 1.0.0; source-sha256 772afdac7b4affe67543265be7d4c7a69c0941305b360cbd4cbf4e76ab883aaa. Edit the central document, not this snapshot. -->

# Saga参与者适配

类别：`saga-participant`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

把单个事务资源的阶段动作和证据接口连接到协调器；参与者不拥有全局事务裁决。

## 输入与人工信息

参与者ID、resource、每阶段命令/参数与幂等/证据政策需明确。

## 生产与所有权

由列出的生产者/实现提供。BC的Scala结构与JS最终写入者必须一起判断；同文件后续JS覆写时，最终台账行归最后写入者。当前银行兼容profile只按实际已有产物标识，不由旧文档推定能力。

目录状态：`implemented`；所有权：`mixed`。生成文件通过公共生成/更新通道维护；独立手写实现不是生成结果的可编辑副本。

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

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

适用：saga-strict / bc-strict / js-formal / bank-p23。当前saga-core基线4352a317的strict API与银行P23消费有明确合同，Monarch R2不自动升级Saga政策或retry/backoff/deadline。

- `saga:src/main/scala/net/imadz/infra/saga/SagaParticipant.scala` — `SagaParticipant`。
- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/application/transactor/SagaParticipantTemplate.scala` — `SagaParticipantTemplate`。
- `saga:src/main/scala/net/imadz/infra/saga/strict/StrictSagaDefinition.scala` — `StrictSagaDefinition`。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。
