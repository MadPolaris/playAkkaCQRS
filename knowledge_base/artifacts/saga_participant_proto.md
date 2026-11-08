<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/saga/saga-wire.md; catalog 2.3.0; source-sha256 486e1b5e29af599bd86decd3de35ced76aec1c5f20ac9b4e30a244a0c873b016. Edit the central document, not this snapshot. -->

# Saga持久化协议与参与者线格式

类别：`saga-wire`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

定义Saga协调/阶段状态、回执及持久化线格式，不把逻辑参与者对象直接作为新通用wire。

## 输入与人工信息

字段号、阶段/决定、args和receipt bytes、定义/codec pin须显式。

## 生产与所有权

- `saga-legacy`消费库的`saga_v3.proto`，`saga-strict`消费库的`saga_strict.proto`；这些通用协议不是每个业务生成的wire。
- `js-formal`/`bank-p23`由`renderHostAdapters`生成应用`<host>_host.proto`及HostSerializer；`bc-semantic-products`由ProductHostSupportTemplate独立生成。应用host-proto是真实生成能力，不能为了修正库归属而删掉。
- `js-run-all-strict`产生strict wire辅助Scala源码，不是输出一份新的`.proto`或重建库schema。

逐profile的产物、库消费与证据级别见CAPABILITY-MATRIX.md（公共包 docs/artifacts/CAPABILITY-MATRIX.md）。P23冻结字段、PID、codec与旧journal不因分类修订而改变。

## 结构与命名

legacy saga_v3.proto和strict saga_strict.proto分开；应用host自己的proto属于其宿主协议，不与库wire共用身份。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

库serializer和converter处理具体持久类型；禁止宽绑定内部callback/Actor命令。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

旧wire/新wire各自roundtrip、未知枚举/缺pin/错字段拒绝；不能在原PID上直接替换不兼容协议。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

## 版本与实例

legacy/strict库schema、JS/BC应用host proto与run-all Scala wire helper各自解释。测试源码不等wire往返/恢复已验证；P23冻结，最终批准未授予。

- `saga:src/main/protobuf/saga_v3.proto` — legacy库schema。
- `saga:src/main/protobuf/saga_strict.proto` — strict库schema。
- `js:generators/scala-cqrs-application/render-host-adapters.mjs` — `renderHostAdapters`（应用host proto）。
- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/products/ProductHostSupportTemplate.scala` — `generate`（独立BC host支持）。
- `js:generators/saga-dsl/emit-strict.mjs` — `emitStrictFiles`（Scala辅助源码）。

来源revision、摘要及处置见sources.lock.json（公共包 docs/artifacts/sources.lock.json）；不授权不兼容journal切换。
