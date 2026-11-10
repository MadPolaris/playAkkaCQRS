<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/projection/read-model.md; catalog 2.6.0; source-sha256 c99f74cd9a6bcfc864dc4a1b067a3746191220b8c54340825c417c16e71f6030. Edit the central document, not this snapshot. -->

# 读模型与视图定义

类别：`read-model`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

定义查询需要的派生事实/行形状及投影装配入口，不是写侧聚合的替代真源。旧Projection对象可能包含sourceProvider和组合分发器，因此其运行机制应位于glue。

## 输入与人工信息

视图字段、identity、source events、存储和更新/删除语义必须给定。说明性read-model只是知识，不能被猜成SQL程序。

## 生产与所有权

- `bc-single` / `bc-multi`：BC 行 DTO 来自 `ProjectionRepositoryTemplate`；`ProjectionTemplate` 主要生成 Akka 投影运行接线，不能混记为同一个纯行 producer。
- `js-formal`：`renderJournalRepository` 生成 row/index 记录；`renderJournalReadside` 负责投影机制，不能独占行类型来源。
- `js-legacy`：`readmodel-dsl` 只生成记录和未实现说明，`projection-dsl` 生成 Akka view，`viewrepo` 生成仓储 DTO；`js-retired` 另有退役 projection emitter。
- 说明性读模型不等于可执行 SQL 声明；声明字段、生成结构、补齐 handler 和查询行为需分别判断。

逐 profile 的生命周期、生产链与证据层级见派生能力矩阵（公共包 docs/artifacts/CAPABILITY-MATRIX.md）。可用源码、生成接线、目标编译、行为验证和最终批准不是同一声明。

## 结构与命名

BC DecisionView及Projection通常位于application/projection，模块glue；纯视图/类型可在kernel。JS旧readmodel emitter和当前journal-readside profile是不同输入合同。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

依赖事件协议、查询Repository和实际ProjectionBootstrap；SQL view与图/向量索引的最终一致性分别描述。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

逐事件视图更新、重放、分页/身份、失效和删除规则；不能用页面看见一行证明全水位追平。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

## 版本与实例

本页按上述 profile 区分当前原生、保留/退役路径与手写参考；不跨 profile 传播实现或验收结论。完整来源 revision、摘要和生产链由目录、派生矩阵及 `sources.lock.json` 记录，本页不另抄暂定统计。

## 第六批有限 persistence-readside 候选登记

本类新增 `js-persistence-readside-candidate-v1`，由显式 `scala-cqrs-persistence-readside-v1`／`scala-cqrs-persistence-readside@1` 提供；模块为 `glue`。本段只登记实际源结构，不宣称本批已完成或已编译。

实际输出合同（可覆盖同一物理文件中的不同职责，不按合同数冒充文件数）

- `readside-envelope`／`read-model`：Bounded event envelope, stored state and typed index row/page/cursor/result carriers derived from the selected state-map field. 路径 `modules/glue/src/main/scala/**/infrastructure/readside/*.scala`。

输入为唯一业务作者树、原七个 family2 产品及显式 persistence-readside 技术 binding；wire layout SHA 必须核对，不能自动重签。沿用 family2 kernel/glue 和唯一 writer；生成测试与独立 oracle 分开，sbt 定义存在不等任务执行。

本次登记移除旧 active evidence 引用，原24观察和旧回执在独立历史索引（公共包 docs/artifacts/evidence/capability-persistence-readside-prior-current06-history.json）保真；新观察要在生产冻结后单独采集并通过原公共验证器。本段及目录 implemented 仅表示实现存在，当前 generated/compiled/behavior/final-approved 不由本配方晋升。范围与限制见本族合同（公共包 docs/artifacts/scala-cqrs/PERSISTENCE-READSIDE.md）。

读侧文件库重放不是生产 Actor journal 耐久性；不扩张为 MySQL、任意读模型、HTTP、银行/Saga/Monarch 或架构批准。
