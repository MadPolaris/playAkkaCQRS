<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/application/application-service.md; catalog 2.5.0; source-sha256 98cf60438dade0132c6ca3372de33c12d5ef36f0e45d3bb0d3c816eaf9feb007. Edit the central document, not this snapshot. -->

# 应用用例与业务流程服务

类别：`application-service`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

组织用例、授权和聚合交互，不在应用服务里维护独立资金状态。Saga事务和Monarch流程编排职责不可互换。

## 输入与人工信息

用例步骤、身份、权限、依赖端口与终态条件需要知识和实现者明确提供；通用骨架不能猜定投期次政策。

## 生产与所有权

BC由ApplicationServiceBuilder/Template读取SystemMetadata.application.services生成带MANUAL方法区的骨架，仍含???；selfType控制实际包名，多模块当前放root。这不是业务用例已由真源完整实现，也不能把此模板的MANUAL保护推给所有service模板。

JS legacy service-dsl/emit.mjs#emitApplicationService读取显式kind=application、依赖和方法定义，真正写出<name>.scala，但方法同样为骨架，未接入正式typed-product注册链。其复制的rules_domain_gen头不改变JS写入者归属。正式Play service的Endpoint.run/Bindings属于应用流程调用合同，不应冒充本页的业务服务实现。

bank-p23的BankSipApplication是外置手写实现。各profile的骨架、手写算法和证据见能力矩阵（公共包 docs/artifacts/CAPABILITY-MATRIX.md）；源码存在不等于本轮生成、编译、用例验收或最终批准。

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

主要实例：bc-single / bc-multi / bank-p23及JS legacy service-dsl。接口/骨架可生成与完整用例实现是不同能力；银行手写定投服务不由通用骨架自动重建。

- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/application/ApplicationServiceTemplate.scala` — `ApplicationServiceTemplate`。
- `js:generators/service-dsl/emit.mjs` — `emitApplicationService`。
- `bank:handwritten/scala/example/bankwealth/sip/BankSipApplication.scala` — `BankSipApplication`。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。

### 类别依赖

`application-flow-port`、`aggregate-repository-port`。这些是职责依赖，不代替真实Scala import或执行顺序验证。
