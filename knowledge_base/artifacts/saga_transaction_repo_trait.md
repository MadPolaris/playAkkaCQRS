<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/saga/saga-transaction-repository-port.md; catalog 2.3.0; source-sha256 c4018420db5a384cc8672f7c3917b550988069b0278ff4fef236e4e4e7f546bd. Edit the central document, not this snapshot. -->

# Saga事务仓储接口

类别：`saga-transaction-repository-port`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

对调用者提供按交易/运行身份获取事务句柄的接口，不让业务层猜actor路径。

## 输入与人工信息

事务身份、结果查询/启动语义与生命周期策略必须明确；旧repository不是strict运行库必需新类型。

## 生产与所有权

- `saga-legacy`保留历史repository架构角色的reference-only关联；运行库SagaRunner不是一个必需的通用strict repository trait。
- `play-legacy`有真实手写FabSagaTransactionRepository应用接口；`bank-p23`有手写BankRuntimeDirectory端口，具体寻址/生命周期由BankRuntime负责。两者不共享同一生成合同。

逐profile责任和来源见CAPABILITY-MATRIX.md（公共包 docs/artifacts/CAPABILITY-MATRIX.md）。不因旧标签是reference-only而抹去现有应用端口，也不虚构统一生成接口；P23与R2采用边界不变。

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

历史角色、Play手写repository和bank手写directory按profile分别解释。测试源码不等查找/恢复已验证；接口存在不授予最终批准，也不迁移P23。

- `play:app/net/imadz/application/services/transactor/FabSagaTransactionRepository.scala` — `FabSagaTransactionRepository`。
- `saga:src/main/scala/net/imadz/infra/saga/dsl/SagaRunner.scala` — `SagaRunner`（替代门面）。
- `bank:handwritten/scala/example/bankwealth/integration/BankRuntimeDirectory.scala` — `BankRuntimeDirectory`。

来源revision、摘要及处置见sources.lock.json（公共包 docs/artifacts/sources.lock.json）；不虚构strict通用repository producer。
