<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/domain/invariant-rule.md; catalog 1.0.0; source-sha256 dea1742e2fa59b009be2dddace67fdb6c8f829b56c033b41a049edf8cb470967. Edit the central document, not this snapshot. -->

# 领域不变式与命令决策

类别：`invariant-rule`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

在给定前状态和参数上验证条件并产生事件或拒绝；它不是Reactive Policy的事件到命令反应。规则顺序和错误优先级是可观察合同。

## 输入与人工信息

guard、emission、参数血缘、错误码、前置与后置条件必须显式。未知业务语义拒绝；外部判定通过已声明port输入，不在纯规则里偷偷查库。

## 生产与所有权

由列出的生产者/实现提供。BC的Scala结构与JS最终写入者必须一起判断；同文件后续JS覆写时，最终台账行归最后写入者。当前银行兼容profile只按实际已有产物标识，不由旧文档推定能力。

目录状态：`implemented`；所有权：`generated`。生成文件通过公共生成/更新通道维护；独立手写实现不是生成结果的可编辑副本。

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

适用：bc-single / bc-multi / js-formal。单模块和多模块是放置/装配变体，不是两套业务事实。BANK P23兼容层与BC目标模板可能保留具名差异；没有把R2运行库能力写成当前银行生成支持。

- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/domain/InvariantRuleTemplate.scala` — `InvariantRuleTemplate`。
- `bc:knowledge_base/design_docs/field_template/tools/rules_domain_gen.mjs` — `checkAggregate`。
- `js:generators/scala-cqrs-domain/render-domain.mjs` — `renderInvariant`。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。

### 类别依赖

`domain-state`、`domain-protocol`、`rule-parameters`、`computation-port`。这些是职责依赖，不代替真实Scala import或执行顺序验证。
