<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/project/aggregate-bootstrap.md; catalog 2.2.0; source-sha256 55ea9fdc7dde1a2d1c74a171c4fa81eb5241c76b4c5d7db5215eabdea67576c0. Edit the central document, not this snapshot. -->

# 聚合持久化Bootstrap

类别：`aggregate-bootstrap`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

将初始State、command/event handlers、adapter和PersistenceId组成实际EventSourcedBehavior，必要时注册sharding。

## 输入与人工信息

PID、tag/retention/backoff、serializer/adapter与port由所选profile显式提供；银行兼容迁移不能顺带套入BC默认策略。

## 生产与所有权

- `bc-single`：BC 的 `BootstrapTemplate` 生成持久化与 sharding/tag/retention/backoff 装配。
- `bc-multi`：同一 BC 生产链将 bootstrap 放到 glue；放置成功不等于多模块构建闭合。
- `js-formal`：`buildAggregateModels → renderAggregateLayers → bootstrapTemplate` 生成 `Bootstrap.create`，由 `applyEntries` 写入；返回具名 PID 的 Behavior，不自行 spawn、注册 sharding 或套用 BC 默认策略。
- `js-legacy`：保留的 `bootstrap-dsl` 生成旧启动接线；`js-retired` 对应已退役 `scala-cqrs-es` 生产链。旧集群启动方式不能充作原生服务能力。

逐 profile 的生命周期、生产链与证据层级见派生能力矩阵（公共包 docs/artifacts/CAPABILITY-MATRIX.md）。可用源码、生成接线、目标编译、行为验证和最终批准不是同一声明。

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

本页按上述 profile 区分当前原生、保留/退役路径与手写参考；不跨 profile 传播实现或验收结论。完整来源 revision、摘要和生产链由目录、派生矩阵及 `sources.lock.json` 记录，本页不另抄暂定统计。

### 类别依赖

`aggregate-root`、`aggregate-factory`、`event-handler`、`event-adapter`、`snapshot-adapter`。这些是职责依赖，不代替真实Scala import或执行顺序验证。
