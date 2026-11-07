<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/project/aggregate-bootstrap.md; catalog 1.0.0; source-sha256 582d0bfc0cdc027379093ddd9328077225c45a80c7747ef7a5c3af00b32aaf2f. Edit the central document, not this snapshot. -->

# 聚合持久化Bootstrap

类别：`aggregate-bootstrap`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

将初始State、command/event handlers、adapter和PersistenceId组成实际EventSourcedBehavior，必要时注册sharding。

## 输入与人工信息

PID、tag/retention/backoff、serializer/adapter与port由所选profile显式提供；银行兼容迁移不能顺带套入BC默认策略。

## 生产与所有权

由列出的生产者/实现提供。BC的Scala结构与JS最终写入者必须一起判断；同文件后续JS覆写时，最终台账行归最后写入者。当前银行兼容profile只按实际已有产物标识，不由旧文档推定能力。

目录状态：`implemented`；所有权：`generated`。生成文件通过公共生成/更新通道维护；独立手写实现不是生成结果的可编辑副本。

## 结构与命名

infrastructure/bootstrap/<Name>Bootstrap，multi在glue；JS Factory.create返回Behavior并由bank owner spawn。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

只在此组合持久化机制；事件应用纯函数与命令行为分离，旧facade可转发但不是新设计内层依赖许可。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

Factory到实际Behavior的消费、PID/adapter、崩溃恢复与Actor testkit；checkpoint不等全业务exactly-once。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

## 版本与实例

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

适用：bc-single / bc-multi / js-formal。单模块和多模块是放置/装配变体，不是两套业务事实。BANK P23兼容层与BC目标模板可能保留具名差异；没有把R2运行库能力写成当前银行生成支持。

- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/infrastructure/bootstsrap/BootstrapTemplate.scala` — `BootstrapTemplate`。
- `js:generators/scala-cqrs-play-service/render-aggregate-layers.mjs` — `EventSourcedBehavior`。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。

### 类别依赖

`aggregate-root`、`aggregate-factory`、`event-handler`、`event-adapter`、`snapshot-adapter`。这些是职责依赖，不代替真实Scala import或执行顺序验证。
