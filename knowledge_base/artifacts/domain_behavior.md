<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/domain/event-handler.md; catalog 2.3.0; source-sha256 43fb22016c57cacd6fed3897f2e805dafe251eb68228323e9bc81e3971924a08. Edit the central document, not this snapshot. -->

# 事件应用与领域行为

类别：`event-handler`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

唯一负责Event与前State到后State的纯折叠；重放同一事件序列得到同一结果。不发送命令、调用TA或在重放时制造效果。

## 输入与人工信息

输入为event字段、fold assignments、前状态求值顺序和显式纯计算。未知event如何处理必须是合同，不自动静默忽略。

## 生产与所有权

BC由EntityBehaviorBuilder → EntityBehaviorTemplate消费domain.behaviors的mutations/helperMethods；原始模板保留helper ???，未知事件默认case _ => state，不能通称完整fold或未知事件拒绝。选中Metarch pipeline时，BC仓库自己的rules_domain_gen.mjs可依据编译真源、rules/functions与实际签名条件覆写；不是metarch-truthsrc的JS生产者，也不能假定每个BC目标都会经过它。

JS正式fold由render-domain.mjs#renderInvariant消费domain-decision/domain-folds等typed产品；renderAggregate负责状态，不是事件折叠的直接写入者。layered目标生成核心与兼容转发，非layered形在domain下输出。legacy aggregate-dsl的entityDir分支及retired scala-cqrs-es另作历史/兼容生产路径记录。

按profile区分源模板、条件最终内容写入者与完整行为证据，见能力矩阵（公共包 docs/artifacts/CAPABILITY-MATRIX.md）。本轮源码核对不是生成、重放测试或最终批准。

## 结构与命名

BC及JS layered目标以domain/entities/behaviors/<Name>EventHandler为核心，JS layered的旧domain入口是兼容转发；JS非layered目标直接在domain下承载fold。BC生成器还可输出拆分函数/事件方法，物理数量不等类别数量。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

只依赖entity/domain values和注入纯port；EventSourcedBehavior装配同时选择commandHandler和eventHandler。不要把JDBC投影handler与本类型混用。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

每种事件、集合更新/删除/计数顺序与恢复往返；验证旧入口实际转到唯一核心。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

### 第四批有限 aggregate-core 候选（当前登记，非验收）

`js-aggregate-core-candidate-v1` 对应实际入口 `scala-cqrs-aggregate-core-v1`、manifest `scala-cqrs-aggregate-core@1`。kernel纯fold支持set、put、remove、put-all、remove-all、money-apply、integer-apply；全部RHS读同一pre-event State，重复target拒绝，无assignment保持状态，不调用host或decide。纯失败无半更新，但不能承诺已persist journal回滚。

本页登记的物理输出为 `modules/kernel/src/main/scala/**/domain/entities/behaviors/*.scala`。实际来源为 `generators/scala-cqrs-aggregate-core-v1/templates/event-handler.mjs#eventHandlerTemplate`；`generators/scala-cqrs-aggregate-core-v1/build-models.mjs#buildModels`。新入口组合复用的family1生产jobs和本族jobs，经 `generators/scala-cqrs-domain-kernel-v1/render.mjs#renderJobs` 与固定 `entry.mjs#aggregateSourceEntry` owner，最后由 `src/core/generate.mjs#applyEntries` 写入；复用指向原物理模板，不存在新族VO/PB复制模板。

`implemented` 仅表示上述源码存在，source仍活跃，未声称最终核验。当前无generation/compiled/behavior/final-approved观察；全量strict比较、冻结后锁与真实双lane由主集成负责。原16条观察及74条变体引用已原样保入历史索引（公共包 docs/artifacts/evidence/capability-domain-kernel-final-history.json），原receipt路径/字节不动、不续签，其旧锁下的资格不算current；初/二/三批原验收仍保留。构建只可取得generation资格。有限范围见本族合同（公共包 docs/artifacts/scala-cqrs/AGGREGATE-CORE.md）；无Bank、外部结果桥、完整四模块/服务或最终批准。

## 版本与实例

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

主要实例：bc-single / bc-multi / js-formal；legacy/retired路径见上文。BC条件覆写不能传播给所有目标，JS layered与非layered也不是同一文件布局。

- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/domain/EntityBehaviorTemplate.scala` — `EntityBehaviorTemplate`。
- `bc:knowledge_base/design_docs/field_template/tools/rules_domain_gen.mjs` — Metarch条件最终内容写入。
- `js:generators/scala-cqrs-domain/render-domain.mjs` — `renderInvariant`（事件fold，不是`renderAggregate`）。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。

### 类别依赖

`domain-entity`、`domain-protocol`、`computation-port`。这些是职责依赖，不代替真实Scala import或执行顺序验证。
