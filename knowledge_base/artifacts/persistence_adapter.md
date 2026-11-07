<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/persistence/event-adapter.md; catalog 2.2.0; source-sha256 f21fa09d5532b49b784c622ed8ec6b561f1e97186751dc13837cc1398cdc712d. Edit the central document, not this snapshot. -->

# 事件持久化适配器

类别：`event-adapter`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

在Akka journal与领域Event间转换；不在replay时执行业务副作用或新增事件。

## 输入与人工信息

事件族、PB转换和版本/upcast规则；未知历史消息如何处理需要显式迁移策略。

## 生产与所有权

BC由AggregateAdapterBuilder → EventAdapterTemplate读取聚合event/PB/converter元数据，输出infrastructure/persistence/<Name>EventAdapter.scala，多模块放glue。

JS正式render-adapters.mjs#renderAdapters基于serializationModel与准入domain-folds、exact值/codec等产品，在<Name>Adapters.scala内生成EventAdapter和SnapshotAdapter；两个类别共享一个物理文件。legacy adapter-dsl另输出Akka事件适配器，retired scala-cqrs-es保留历史实现，两者与正式typed路径分开记录。

各profile的输出、生成责任和生命周期见能力矩阵（公共包 docs/artifacts/CAPABILITY-MATRIX.md）。适配器源码存在不是历史upcast策略已实现，也不是本轮journal读写、目标编译或最终批准。

## 结构与命名

<Name>EventAdapter可能与SnapshotAdapter同处<Name>Adapters.scala；一文件多类别应保留关联。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

Bootstrap通过.eventAdapter安装；依赖converter/serializer，领域核心不import adapter。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

真实journal写读、EventSeq语义、历史事件缺字段与损坏/未知类型拒绝。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

## 版本与实例

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

主要实例：bc-single / bc-multi / js-formal；legacy/retired另见上文。BC独立文件与JS合并Adapters文件分别计入实际输出，不能由同类名称推定相同载体或升级策略。

- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/infrastructure/persistence/EventAdapterTemplate.scala` — `EventAdapterTemplate`。
- `js:generators/scala-cqrs-domain/render-adapters.mjs` — `renderAdapters`。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。

### 类别依赖

`proto-converter`、`domain-protocol`。这些是职责依赖，不代替真实Scala import或执行顺序验证。
