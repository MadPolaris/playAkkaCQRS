<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/application/aggregate-behavior.md; catalog 2.5.0; source-sha256 9b2a0907ad650945fff574141c6e701b4833099a7fbf314486913845e0dba5a6. Edit the central document, not this snapshot. -->

# 聚合命令行为

类别：`aggregate-behavior`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

校验authority，分派请求到领域Rules，把事件交给持久化Effect，并在指定提交边界回复。不另建领域决策或在状态对象里隐式写值。

## 输入与人工信息

明确的命令handler合同、Rules、依赖port与成功/拒绝回复形式；外部source的authority是调用者提供的真实安全决策。

## 生产与所有权

BC从SystemMetadata.application.aggregates经AggregateBehaviorBuilder/Template生成行为结构；原始分支仍有???/未处理helper。原生formal行为与Metarch专用rules_domain_gen.mjs后写内容必须区分；后者属于BC仓库，消费编译真源、rules/functions及已暂存签名，不能假定非Metarch目标也会补全。暂存内容生成与最终apply是不同阶段。

JS正式Play service由buildAggregateModels → templates/aggregate-behavior.mjs#behaviorTemplate消费domain-decision/domain-folds等typed产品。legacy aggregate-dsl也生成命令mixin，未支持分支可含MANUAL ???；retired scala-cqrs-es仅代表历史骨架。authority策略和真实计算实现仍由宿主提供。

各profile的骨架、条件最终写入者和证据见能力矩阵（公共包 docs/artifacts/CAPABILITY-MATRIX.md）。源码路径已识别不等于本轮目标生成、持久化行为测试、编译或最终批准。

## 结构与命名

application/aggregates/behaviors/<Name>Behaviors。BC初始skeleton在选定Metarch链中可被BC仓库的Node rules脚本后写，需同时记录生产者和条件；JS正式Play service仅对准入决策产品生成行为，不能把未实现桩或宿主计算接口当完成的业务实现。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

依赖领域规则、Protocol和计算接口，Bootstrap提供实际port。持久化完成前不能把资金操作报告为成功。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

事件/无事件/拒绝分支、reply先后、authority拒绝、actor persistence testkit；不变测试预期独立维护。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

### 第四批有限 aggregate-core 候选（当前登记，非验收）

`js-aggregate-core-candidate-v1` 对应实际入口 `scala-cqrs-aggregate-core-v1`、manifest `scala-cqrs-aggregate-core@1`。本族行为在同一Aggregate文件内，query/reject/empty不persist；非空Vector一次persist，仅ACK后的thenReply返回Accepted。恢复只fold；host/写拒绝/写失败无Accepted。与aggregate-root共用物理sample，不额外产生Behaviors文件。

本页登记的物理输出为 `modules/glue/src/main/scala/**/application/aggregates/*.scala`。实际来源为 `generators/scala-cqrs-aggregate-core-v1/templates/aggregate.mjs#aggregateTemplate`；`generators/scala-cqrs-aggregate-core-v1/build-models.mjs#buildModels`。新入口组合复用的family1生产jobs和本族jobs，经 `generators/scala-cqrs-domain-kernel-v1/render.mjs#renderJobs` 与固定 `entry.mjs#aggregateSourceEntry` owner，最后由 `src/core/generate.mjs#applyEntries` 写入；复用指向原物理模板，不存在新族VO/PB复制模板。

冻结工具/来源指纹下，catalog当前实际登记20份观察（原12份样例generated；family1/family2各1份complete-plan generated及3份同样例源码generated/compiled/behavior-verified）。本页本族源码已取得同一Warehouse样例的generated、compiled、behavior-verified资格，包含实际生成的支持测试；只证明该冻结输入与最终产物字节，不推广至全profile，final-approved=0。原16条观察及74条变体引用已原样保入历史索引（公共包 docs/artifacts/evidence/capability-domain-kernel-final-history.json），原receipt路径/字节不动、不续签，其旧锁下的资格不算current；初/二/三批原验收仍保留。两类build只取得generated资格，不借源码编译/行为回执晋升。有限范围见本族合同（公共包 docs/artifacts/scala-cqrs/AGGREGATE-CORE.md）；无Bank、外部结果桥、完整四模块/服务或最终批准。

## 版本与实例

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

主要实例：bc-single / bc-multi / js-formal；legacy/retired另见上文。BC条件rules后写与JS正式typed链分属不同仓库，单/多模块放置也不推定业务完成或R2支持。

- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/application/AggregateBehaviorTemplate.scala` — `AggregateBehaviorTemplate`。
- `bc:knowledge_base/design_docs/field_template/tools/rules_domain_gen.mjs` — Metarch条件后写。
- `js:generators/scala-cqrs-play-service/templates/aggregate-behavior.mjs` — `behaviorTemplate`。
- `js:generators/scala-cqrs-play-service/render-aggregate-layers.mjs` — `renderAggregateLayers`。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。

### 类别依赖

`application-protocol`、`invariant-rule`、`command-helper`。这些是职责依赖，不代替真实Scala import或执行顺序验证。

## 第六批有限 persistence-readside 候选登记

本类新增 `js-persistence-readside-candidate-v1`，由显式 `scala-cqrs-persistence-readside-v1`／`scala-cqrs-persistence-readside@1` 提供；模块为 `glue`。本段只登记实际源结构，不宣称本批已完成或已编译。

实际输出合同（可覆盖同一物理文件中的不同职责，不按合同数冒充文件数）

- `aggregate-root`／`aggregate`：One application Aggregate assembles actual command behavior and pure fold. Same physical aggregate output/sample serves aggregate-root and aggregate-behavior; no extra facade or duplicated category/file. 路径 `modules/glue/src/main/scala/**/application/aggregates/*.scala`。

输入为唯一业务作者树、原七个 family2 产品及显式 persistence-readside 技术 binding；wire layout SHA 必须核对，不能自动重签。沿用 family2 kernel/glue 和唯一 writer；生成测试与独立 oracle 分开，sbt 定义存在不等任务执行。

本次登记移除旧 active evidence 引用，原24观察和旧回执在独立历史索引（公共包 docs/artifacts/evidence/capability-persistence-readside-prior-current06-history.json）保真；新观察要在生产冻结后单独采集并通过原公共验证器。本段及目录 implemented 仅表示实现存在，当前 generated/compiled/behavior/final-approved 不由本配方晋升。范围与限制见本族合同（公共包 docs/artifacts/scala-cqrs/PERSISTENCE-READSIDE.md）。

读侧文件库重放不是生产 Actor journal 耐久性；不扩张为 MySQL、任意读模型、HTTP、银行/Saga/Monarch 或架构批准。
