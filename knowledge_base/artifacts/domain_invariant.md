<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/domain/invariant-rule.md; catalog 2.1.0; source-sha256 0bcb98fe0d01d9a67af4fdef6d8b7bc8d8b976c10530b19635260f2544cd9a1d. Edit the central document, not this snapshot. -->

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
