<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/saga/saga-wire.md; catalog 1.0.0; source-sha256 b978f78d20a22f45ba727e5b2ef6c8d977855606b5c040fc9c19cf55cfb24183. Edit the central document, not this snapshot. -->

# Saga持久化协议与参与者线格式

类别：`saga-wire`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

定义Saga协调/阶段状态、回执及持久化线格式，不把逻辑参与者对象直接作为新通用wire。

## 输入与人工信息

字段号、阶段/决定、args和receipt bytes、定义/codec pin须显式。

## 生产与所有权

由列出的生产者/实现提供。BC的Scala结构与JS最终写入者必须一起判断；同文件后续JS覆写时，最终台账行归最后写入者。当前银行兼容profile只按实际已有产物标识，不由旧文档推定能力。

目录状态：`implemented`；所有权：`library`。生成文件通过公共生成/更新通道维护；独立手写实现不是生成结果的可编辑副本。

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

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

适用：saga-legacy / saga-strict。当前saga-core基线4352a317的strict API与银行P23消费有明确合同，Monarch R2不自动升级Saga政策或retry/backoff/deadline。

- `saga:src/main/protobuf/saga_v3.proto` — `syntax`。
- `saga:src/main/protobuf/saga_strict.proto` — `syntax`。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。
