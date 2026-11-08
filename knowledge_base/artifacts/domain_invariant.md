<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/domain/invariant-rule.md; catalog 2.3.0; source-sha256 3401b7884791504c64a4e1e92c0b9ccf7e24b115fe37842d917696daee05bbc0. Edit the central document, not this snapshot. -->

# 领域不变式与命令决策

类别：`invariant-rule`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

在给定前状态和参数上验证条件并产生事件或拒绝；它不是Reactive Policy的事件到命令反应。规则顺序和错误优先级是可观察合同。

## 输入与人工信息

guard、emission、参数血缘、错误码、前置与后置条件必须显式。未知业务语义拒绝；外部判定通过已声明port输入，不在纯规则里偷偷查库。

## 生产与所有权

BC原始InvariantRuleBuilder/Template读取domain.invariants，但模板条件文本被禁用且留有???。AggregateRulesBuilder/Template的rules-formal分支、Metarch pipeline中BC-owned rules_domain_gen.mjs的条件覆写是不同生产路径；后者读取编译真源、generated_rules、functions/ports与实际Protocol/Entity签名，不能把这些能力归给骨架模板或metarch-truthsrc。

JS正式render-domain.mjs#renderInvariant以domain-decision/domain-folds等准入typed产品生成有序决策。legacy invariant-dsl仍使用旧翻译表和选定元数据；retired scala-cqrs-es-rules是历史overlay。三者不是同一实现，也不因输出叫Rules就具有相同完整性。

具体profile及路径的部分实现、条件写入和证据见能力矩阵（公共包 docs/artifacts/CAPABILITY-MATRIX.md）。本页保留纯度/拒绝未知语义的目标，不把源码扫描当执行验证或最终批准。

## 结构与命名

BC有聚合Rules容器/内嵌Rule以及需port的class变体；JS生成Either[String,Vector[Event]]核心和兼容薄入口。成员目标lowerCamelCase；当前直接沿用ReserveFunds等大写方法记录为命名债，不改稳定知识ID。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

只依赖领域状态/参数/纯计算接口；由application行为调用后持久化事件。Instant.now等非确定值须来自显式时钟输入。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

顺序、幂等/冲突先后、错误优先、外部输入与生成器独立oracle；不因为当前实现含异常或旧示例调用时钟而放宽纯度目标。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

### 第四批有限 aggregate-core 候选（当前登记，非验收）

`js-aggregate-core-candidate-v1` 对应实际入口 `scala-cqrs-aggregate-core-v1`、manifest `scala-cqrs-aggregate-core@1`。kernel Rules提供decide(state, command, hosts): Either[String, Vector[Event]]及命令方法，按sourceOrdinal短路reject/succeed-empty；只消费准入表达式，不偷偷I/O、不猜错误码。Long checked运算与exact/unit约束保留。

本页登记的物理输出为 `modules/kernel/src/main/scala/**/domain/invariant/*.scala`。实际来源为 `generators/scala-cqrs-aggregate-core-v1/templates/rules.mjs#rulesTemplate`；`generators/scala-cqrs-aggregate-core-v1/build-models.mjs#buildModels`。新入口组合复用的family1生产jobs和本族jobs，经 `generators/scala-cqrs-domain-kernel-v1/render.mjs#renderJobs` 与固定 `entry.mjs#aggregateSourceEntry` owner，最后由 `src/core/generate.mjs#applyEntries` 写入；复用指向原物理模板，不存在新族VO/PB复制模板。

冻结工具/来源指纹下，catalog当前实际登记20份观察（原12份样例generated；family1/family2各1份complete-plan generated及3份同样例源码generated/compiled/behavior-verified）。本页本族源码已取得同一Warehouse样例的generated、compiled、behavior-verified资格，包含实际生成的支持测试；只证明该冻结输入与最终产物字节，不推广至全profile，final-approved=0。原16条观察及74条变体引用已原样保入历史索引（公共包 docs/artifacts/evidence/capability-domain-kernel-final-history.json），原receipt路径/字节不动、不续签，其旧锁下的资格不算current；初/二/三批原验收仍保留。两类build只取得generated资格，不借源码编译/行为回执晋升。有限范围见本族合同（公共包 docs/artifacts/scala-cqrs/AGGREGATE-CORE.md）；无Bank、外部结果桥、完整四模块/服务或最终批准。

## 版本与实例

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

主要实例：bc-single / bc-multi / js-formal；legacy/retired规则路径见上文。BC骨架、原生rules-formal及条件Node后写不能混为同一模板实现，BANK P23兼容或R2能力也不能由此推断。

- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/domain/InvariantRuleTemplate.scala` — `InvariantRuleTemplate`。
- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/application/AggregateRulesTemplate.scala` — `AggregateRulesTemplate`，原生formal分支。
- `bc:knowledge_base/design_docs/field_template/tools/rules_domain_gen.mjs` — Metarch条件rules生产路径（含`checkAggregate`检查）。
- `js:generators/scala-cqrs-domain/render-domain.mjs` — `renderInvariant`。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。

### 类别依赖

`domain-state`、`domain-protocol`、`rule-parameters`、`computation-port`。这些是职责依赖，不代替真实Scala import或执行顺序验证。
