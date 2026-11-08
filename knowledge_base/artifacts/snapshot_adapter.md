<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/persistence/snapshot-adapter.md; catalog 2.5.0; source-sha256 4f9f629f870dfb7d1fa8e8c74d2fe67acc7bee57af9fa5f837241eb4883705a0. Edit the central document, not this snapshot. -->

# 快照持久化适配器

类别：`snapshot-adapter`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

把已提交State转换为快照表示并恢复，不把快照当新业务事实或允许跳过必要journal版本检查。

## 输入与人工信息

状态字段、schema版本、codec与快照策略显式配置；retention/tag/backoff不能照抄旧BC默认改变银行行为。

## 生产与所有权

BC由AggregateAdapterBuilder → SnapshotAdapterTemplate读取聚合state/PB/converter元数据，输出infrastructure/persistence/<Name>SnapshotAdapter.scala，多模块放glue。

JS正式render-adapters.mjs#renderAdapters基于serializationModel与domain-folds、exact值/codec等typed产品，在<Name>Adapters.scala中同时输出快照和事件适配器；两个职责不是两个物理文件。legacy adapter-dsl按具名state converter生成快照适配，retired scala-cqrs-es保留历史模板，不能当作正式路径额外完成的能力。

各profile的输出和生成责任见能力矩阵（公共包 docs/artifacts/CAPABILITY-MATRIX.md）。适配器生产源码不决定retention/backoff等宿主政策，也不证明本轮快照恢复、目标编译或最终批准。

## 结构与命名

<Name>SnapshotAdapter，可能与event adapter同文件；multi置glue，Bootstrap安装。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

与领域State、converter及journal恢复配套；单次快照可读不证明业务副作用exactly-once。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

快照保存/恢复、旧State字段和后续事件重放、缺结果bytes不得重跑副作用补齐。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

## 版本与实例

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

主要实例：bc-single / bc-multi / js-formal；legacy/retired另见上文。BC单/多模块是放置变体，JS合并Adapters文件是不同布局；不能把模板存在说成银行恢复或R2能力已经验证。

- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/infrastructure/persistence/SnapshotAdapterTemplate.scala` — `SnapshotAdapterTemplate`。
- `js:generators/scala-cqrs-domain/render-adapters.mjs` — `renderAdapters`。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。

### 类别依赖

`proto-converter`、`domain-state`。这些是职责依赖，不代替真实Scala import或执行顺序验证。

## 第六批有限 persistence-readside 候选登记

本类新增 `js-persistence-readside-candidate-v1`，由显式 `scala-cqrs-persistence-readside-v1`／`scala-cqrs-persistence-readside@1` 提供；模块为 `glue`。本段只登记实际源结构，不宣称本批已完成或已编译。

实际输出合同（可覆盖同一物理文件中的不同职责，不按合同数冒充文件数）

- `persistence-snapshot-adapter`／`snapshot-adapter`：Typed state SnapshotAdapter that rejects foreign or incompatible frames. 路径 `modules/glue/src/main/scala/**/infrastructure/persistence/*.scala`。

输入为唯一业务作者树、原七个 family2 产品及显式 persistence-readside 技术 binding；wire layout SHA 必须核对，不能自动重签。沿用 family2 kernel/glue 和唯一 writer；生成测试与独立 oracle 分开，sbt 定义存在不等任务执行。

本次登记移除旧 active evidence 引用，原24观察和旧回执在独立历史索引（公共包 docs/artifacts/evidence/capability-persistence-readside-prior-current06-history.json）保真；新观察要在生产冻结后单独采集并通过原公共验证器。本段及目录 implemented 仅表示实现存在，当前 generated/compiled/behavior/final-approved 不由本配方晋升。范围与限制见本族合同（公共包 docs/artifacts/scala-cqrs/PERSISTENCE-READSIDE.md）。

读侧文件库重放不是生产 Actor journal 耐久性；不扩张为 MySQL、任意读模型、HTTP、银行/Saga/Monarch 或架构批准。
