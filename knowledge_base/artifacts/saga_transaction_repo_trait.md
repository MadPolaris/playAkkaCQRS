<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/saga/saga-transaction-repository-port.md; catalog 1.0.0; source-sha256 b1177eaba9a3de61f82ca7a943b1417b0c59d296a2f398490b01e34b2c68d217. Edit the central document, not this snapshot. -->

# Saga事务仓储接口

类别：`saga-transaction-repository-port`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

对调用者提供按交易/运行身份获取事务句柄的接口，不让业务层猜actor路径。

## 输入与人工信息

事务身份、结果查询/启动语义与生命周期策略必须明确；旧repository不是strict运行库必需新类型。

## 生产与所有权

由列出的生产者/实现提供。BC的Scala结构与JS最终写入者必须一起判断；同文件后续JS覆写时，最终台账行归最后写入者。当前银行兼容profile只按实际已有产物标识，不由旧文档推定能力。

目录状态：`reference-only`；所有权：`mixed`。生成文件通过公共生成/更新通道维护；独立手写实现不是生成结果的可编辑副本。

## 结构与命名

历史SagaTransactionRepository的职责可由SagaRunner或宿主directory承担；文档解释当前替代而非制造不存在的新trait。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

具体寻址实现外置，保持同一交易namespace和定义版本。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

稳定查找、重复attach、冲突请求和未知状态，不使用随机ID重试旧事务。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

## 版本与实例

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

适用：saga-legacy / bank-p23。旧Saga DSL/TCC与当前strict API是并列合同；不能把strict证据和恢复保证套用旧StepExecutor。

- `saga:src/main/scala/net/imadz/infra/saga/dsl/SagaRunner.scala` — `SagaRunner`。
- `bank:handwritten/scala/example/bankwealth/integration/BankRuntimeDirectory.scala` — `BankRuntimeDirectory`。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。
