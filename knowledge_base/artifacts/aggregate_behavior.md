<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/application/aggregate-behavior.md; catalog 2.3.0; source-sha256 82393926a23db8f414546b4e8b47e23da92dbe6455adf225919af6a6d95990c0. Edit the central document, not this snapshot. -->

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

`implemented` 仅表示上述源码存在，source仍活跃，未声称最终核验。当前无generation/compiled/behavior/final-approved观察；全量strict比较、冻结后锁与真实双lane由主集成负责。原16条观察及74条变体引用已原样保入历史索引（公共包 docs/artifacts/evidence/capability-domain-kernel-final-history.json），原receipt路径/字节不动、不续签，其旧锁下的资格不算current；初/二/三批原验收仍保留。构建只可取得generation资格。有限范围见本族合同（公共包 docs/artifacts/scala-cqrs/AGGREGATE-CORE.md）；无Bank、外部结果桥、完整四模块/服务或最终批准。

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
