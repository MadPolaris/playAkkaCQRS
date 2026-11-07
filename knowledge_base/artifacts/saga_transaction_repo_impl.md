<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/saga/saga-transaction-repository-implementation.md; catalog 2.1.0; source-sha256 77d5832e0c002154fa448dbf4d61f18708f03528516a6422710459416352a8c9. Edit the central document, not this snapshot. -->

# Saga事务寻址实现

类别：`saga-transaction-repository-implementation`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

实现事务定位、恢复后重建与缓存，只管理引用，不把内存当交易事实。

## 输入与人工信息

真实PID/definition/args identity、actor owner与查证授权由宿主给出。

## 生产与所有权

- `saga-legacy`保留历史repository实现角色的reference-only说明；SagaRunner是替代门面，不能据它构造不存在的统一RepoImpl producer。
- `bank-p23`的BankRuntime.ensureCash/ensureRefund及directory是手写集成，实现actor引用、身份、缓存和生命周期管理；调用生成Definition/Host不使BankRuntime变成生成文件。

逐profile的责任及来源见CAPABILITY-MATRIX.md（公共包 docs/artifacts/CAPABILITY-MATRIX.md）。内存map不是耐久交易事实，更不能由map存在推断JVM重启恢复已验证；P23冻结及失败证据保持原样。

## 结构与命名

历史RepoImpl/sharding与银行BankRuntime map是不同profile；当前没有把它们宣称统一生成的类。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

依赖共享coordinator和已选definition，应用不得并发启动同PID的两个writer。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

同identity同请求附着、不同请求冲突、缓存丢失后恢复及关闭owner。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

## 版本与实例

legacy reference角色与bank-p23手写actor owner分开。测试源码不等缓存重建或跨JVM恢复证据，手写实现存在不授予最终批准。

- `saga:src/main/scala/net/imadz/infra/saga/dsl/SagaRunner.scala` — `SagaRunner`（历史替代门面，不是具名RepoImpl）。
- `bank:handwritten/scala/example/bankwealth/integration/BankRuntime.scala` — `ensureCash`、`ensureRefund`。
- `bank:handwritten/scala/example/bankwealth/integration/BankRuntimeDirectory.scala` — `BankRuntimeDirectory`。

来源revision、摘要及处置见sources.lock.json（公共包 docs/artifacts/sources.lock.json）；P23冻结及service失败结论不变。
