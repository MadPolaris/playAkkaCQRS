<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/saga/saga-bootstrap.md; catalog 2.2.0; source-sha256 14c89fc1b3e1ba03b8b32e5c8885914644d707295524a61bc5a4630df8465298. Edit the central document, not this snapshot. -->

# Saga注册与启动装配

类别：`saga-bootstrap`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

安装definition、resolver、codec与协调器factory，保证使用正确版本/namespace启动事务。

## 输入与人工信息

定义注册时机、type key/PID、依赖对象与授权必须明确；不允许默认为任何用户提供Reconcile。

## 生产与所有权

- `saga-strict`消费库的StrictSagaCoordinator factory；库不替应用拥有actor身份或生命周期。
- `js-formal`的`renderDefinitions`在生成Definition内提供`fromTechnical`/`coordinator`装配；`bc-semantic-products`由ProductApplicationTemplate生成对应接线。`bank-p23`再由手写BankRuntime/directory负责spawn、缓存与生命周期，不能全部归为库或生成代码。
- `bc-strict`由StrictSagaModuleBuilder/Model/Template生成Module内definition/behavior/participant/phase bindings；strict fixture Host/Entry有独立来源，不能引用旧BootstrapTemplate证明strict能力。`js-run-all-strict`亦有独立的旧run-all source入口。
- `bc-single`/`bc-multi`的BootstrapTemplate是legacy saga_v3注册/sharding装配；该`bootstrap` kind在multi模式明确路由`glue`。`play-legacy`是手写旧应用装配。不要把legacy Saga其他未映射kind的缺口套在bootstrap上。

逐profile的模块、生产链及证据级别见CAPABILITY-MATRIX.md（公共包 docs/artifacts/CAPABILITY-MATRIX.md）。本次不迁移P23或升级retry政策。

## 结构与命名

旧registry在sharding初始化前注册；strict可按显式PersistenceId构造Behavior。银行宿主通过生成Definition安装实际PhasePort。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

依赖实际participant/codec对象，不单凭声明ID相信实现。与应用Bootstrap相连接但不升级既有journal格式。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

缺definition/codec/authorizer、重复或错namespace、实际actor启动与恢复。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

## 版本与实例

strict库factory、生成Definition/Module与legacy registry按profile分开。P23冻结及retry/backoff/deadline OPEN项不变；测试源码不等actor启动/恢复已验证，最终批准未授予。

- `saga:src/main/scala/net/imadz/infra/saga/strict/StrictSagaCoordinator.scala` — `StrictSagaCoordinator`（库factory）。
- `js:generators/scala-cqrs-application/render-host.mjs` — `renderDefinitions`。
- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/application/transactor/StrictSagaModuleTemplate.scala` — `generate`（strict）。
- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/products/ProductApplicationTemplate.scala` — `generate`（semantic）。
- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/infrastructure/bootstsrap/BootstrapTemplate.scala` — `renderSagaBootstrap`（legacy）。
- `bank:handwritten/scala/example/bankwealth/integration/BankRuntime.scala` — `ensureCash`、`ensureRefund`。

来源revision、摘要及处置见sources.lock.json（公共包 docs/artifacts/sources.lock.json）；不要跨profile认领生产能力。
