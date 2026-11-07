<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/projection/read-model.md; catalog 1.0.0; source-sha256 04710c44128a2e4a3d67efc361de90c23c6176bc35827a0c28f1d622070ccc0e. Edit the central document, not this snapshot. -->

# 读模型与视图定义

类别：`read-model`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

定义查询需要的派生事实/行形状及投影装配入口，不是写侧聚合的替代真源。旧Projection对象可能包含sourceProvider和组合分发器，因此其运行机制应位于glue。

## 输入与人工信息

视图字段、identity、source events、存储和更新/删除语义必须给定。说明性read-model只是知识，不能被猜成SQL程序。

## 生产与所有权

由列出的生产者/实现提供。BC的Scala结构与JS最终写入者必须一起判断；同文件后续JS覆写时，最终台账行归最后写入者。当前银行兼容profile只按实际已有产物标识，不由旧文档推定能力。

目录状态：`implemented`；所有权：`generated`。生成文件通过公共生成/更新通道维护；独立手写实现不是生成结果的可编辑副本。

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

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

适用：bc-single / bc-multi / js-formal。单模块和多模块是放置/装配变体，不是两套业务事实。BANK P23兼容层与BC目标模板可能保留具名差异；没有把R2运行库能力写成当前银行生成支持。

- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/application/ProjectionTemplate.scala` — `ProjectionTemplate`。
- `js:generators/readmodel-dsl/emit.mjs` — `writeFileSync`。
- `js:generators/scala-cqrs-play-service/render-journal-readside.mjs` — `renderJournalReadside`。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。
