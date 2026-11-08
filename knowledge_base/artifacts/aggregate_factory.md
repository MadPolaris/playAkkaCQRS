<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/application/aggregate-factory.md; catalog 2.3.0; source-sha256 c33d7d3f10c70fc9199cef6e02d4e3b1469c2469ef638d7fc25c8429dbe866e1. Edit the central document, not this snapshot. -->

# 聚合行为工厂接口

类别：`aggregate-factory`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

表达取得或构建聚合入口所需的依赖。BC类封装sharding查找/ask，JS layered trait表达Behavior创建合同；不是另一个领域实体工厂或数据仓库。

## 输入与人工信息

JS调用者显式提供稳定身份、初始状态、计算port/authority。BC输入是聚合元数据，其初始化命令选择是builder启发式；业务正确性仍须明确核对，不能把所有profile都描述为消费同一显式create合同。

## 生产与所有权

BC由AggregateFactoryBuilder → AggregateFactoryTemplate读取application.aggregates，产出注入ClusterSharding的查找/ask类，多模块目前放kernel。buildInitializationMethodOption选择首个有参数的非只读命令作为初始化候选，不能据此推断业务上正确的初始化语义。

JS正式layered Play service的templates/aggregate-factory.mjs#factoryTemplate才生成返回Behavior的trait；非layered render-actor.mjs或兼容domain载体内的Factory是object。legacy aggregate-dsl另有factory trait，retired scala-cqrs-es为历史骨架，均不能替代当前BC类或正式JS合同。

工厂形状、输入与所有权按profile分别解释，见能力矩阵（公共包 docs/artifacts/CAPABILITY-MATRIX.md）。这是源生产路径说明，不代表本轮构建、实际创建测试或最终批准。

## 结构与命名

BC application/aggregates/factories/<Name>AggregateFactory是注入ClusterSharding的类，封装引用查找、ask及可选初始化方法；不是返回Behavior的trait。JS layered application/aggregates/<Name>AggregateFactory才声明create(persistenceId, initial, hosts[, authority]): Behavior[Command]；domain兼容/非layered形为object工厂。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

JS layered Bootstrap实现Factory并装配event/command handlers；BC类由DI取得sharding等依赖后查找/初始化聚合，不能套用前者的实现方式。JS兼容域内Factory只作为旧入口保留，不推荐新模块继续依赖它。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

工厂签名、实际创建路径、依赖缺失拒绝和应用调用闭环。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

### 第四批有限 aggregate-core 候选（当前登记，非验收）

`js-aggregate-core-candidate-v1` 对应实际入口 `scala-cqrs-aggregate-core-v1`、manifest `scala-cqrs-aggregate-core@1`。glue/application/factories中的trait create[J]要求typed PersistenceId、initial、hosts、EventAdapter[Event,J]与SnapshotAdapter[State]；PID非null且非空白，实际identity原字符串不改。无默认identity adapter或隐式PID。

本页登记的物理输出为 `modules/glue/src/main/scala/**/application/factories/*.scala`。实际来源为 `generators/scala-cqrs-aggregate-core-v1/templates/factory.mjs#factoryTemplate`；`generators/scala-cqrs-aggregate-core-v1/build-models.mjs#buildModels`。新入口组合复用的family1生产jobs和本族jobs，经 `generators/scala-cqrs-domain-kernel-v1/render.mjs#renderJobs` 与固定 `entry.mjs#aggregateSourceEntry` owner，最后由 `src/core/generate.mjs#applyEntries` 写入；复用指向原物理模板，不存在新族VO/PB复制模板。

`implemented` 仅表示上述源码存在，source仍活跃，未声称最终核验。当前无generation/compiled/behavior/final-approved观察；全量strict比较、冻结后锁与真实双lane由主集成负责。原16条观察及74条变体引用已原样保入历史索引（公共包 docs/artifacts/evidence/capability-domain-kernel-final-history.json），原receipt路径/字节不动、不续签，其旧锁下的资格不算current；初/二/三批原验收仍保留。构建只可取得generation资格。有限范围见本族合同（公共包 docs/artifacts/scala-cqrs/AGGREGATE-CORE.md）；无Bank、外部结果桥、完整四模块/服务或最终批准。

## 版本与实例

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

主要实例：bc-single / bc-multi / js-formal；legacy/retired另见上文。BC class、JS layered trait和非layered/兼容object分别解释；同名Factory不保证同API或部署语义。

- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/builder/application/AggregateFactoryBuilder.scala` — `buildInitializationMethodOption`。
- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/application/AggregateFactoryTemplate.scala` — `AggregateFactoryTemplate`。
- `js:generators/scala-cqrs-play-service/templates/aggregate-factory.mjs` — `factoryTemplate`。
- `js:generators/scala-cqrs-domain/render-actor.mjs` — `renderActors`，非layered object工厂。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。

### 类别依赖

`aggregate-root`、`application-protocol`。这些是职责依赖，不代替真实Scala import或执行顺序验证。
