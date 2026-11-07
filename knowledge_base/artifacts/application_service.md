<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/application/application-service.md; catalog 1.0.0; source-sha256 420d7e745f980616e057a971c1df716666f2d7b931b2575170033eddeb586ba8. Edit the central document, not this snapshot. -->

# 应用用例与业务流程服务

类别：`application-service`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

组织用例、授权和聚合交互，不在应用服务里维护独立资金状态。Saga事务和Monarch流程编排职责不可互换。

## 输入与人工信息

用例步骤、身份、权限、依赖端口与终态条件需要知识和实现者明确提供；通用骨架不能猜定投期次政策。

## 生产与所有权

由列出的生产者/实现提供。BC的Scala结构与JS最终写入者必须一起判断；同文件后续JS覆写时，最终台账行归最后写入者。当前银行兼容profile只按实际已有产物标识，不由旧文档推定能力。

目录状态：`implemented`；所有权：`mixed`。生成文件通过公共生成/更新通道维护；独立手写实现不是生成结果的可编辑副本。

## 结构与命名

application/services或明确模块下的用例类；银行当前部分服务手写，端口/载体生成。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

消费内层合同和port，通过宿主装配外部实现。跨聚合原子性须由所选Saga保证，不是几个Future的组合自动成立。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

正反场景、权限/幂等/结果未知及原业务身份重放；必要时单独服务/恢复验证。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

## 版本与实例

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

适用：bc-single / bc-multi / bank-p23。单模块和多模块是放置/装配变体，不是两套业务事实。BANK P23兼容层与BC目标模板可能保留具名差异；没有把R2运行库能力写成当前银行生成支持。

- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/application/ApplicationServiceTemplate.scala` — `ApplicationServiceTemplate`。
- `bank:handwritten/scala/example/bankwealth/sip/BankSipApplication.scala` — `BankSipApplication`。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。

### 类别依赖

`application-flow-port`、`aggregate-repository-port`。这些是职责依赖，不代替真实Scala import或执行顺序验证。
