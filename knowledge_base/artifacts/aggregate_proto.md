<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/persistence/protobuf-schema.md; catalog 1.0.0; source-sha256 13580618a8ff71e286ab8f9ab020e21ae1065cbaa85a4be7c282ec8355648624. Edit the central document, not this snapshot. -->

# Protobuf线格式定义

类别：`protobuf-schema`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

定义跨进程/持久化wire消息与字段号码，不与领域事件或Scala类名混成同一身份。

## 输入与人工信息

显式字段号、消息变体、可选性、schema版本和兼容策略；删除/重用字段号不是命名整理。

## 生产与所有权

由列出的生产者/实现提供。BC的Scala结构与JS最终写入者必须一起判断；同文件后续JS覆写时，最终台账行归最后写入者。当前银行兼容profile只按实际已有产物标识，不由旧文档推定能力。

目录状态：`implemented`；所有权：`generated`。生成文件通过公共生成/更新通道维护；独立手写实现不是生成结果的可编辑副本。

## 结构与命名

单项目app/protobuf与多模块glue/src/main/protobuf；生成ScalaPB类型属于构建期managed sources，不是truthsrc直接输出的相同库存。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

由converter、serializer和adapter消费；kernel领域事件不直接依赖glue PO。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

PB往返、旧bytes兼容、缺required业务字段拒绝与枚举分支。proto3线格式可空不等于领域语义可空，读侧容忍须专门decoder而非改共享strict converter。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

## 版本与实例

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

适用：bc-single / bc-multi / js-formal。单模块和多模块是放置/装配变体，不是两套业务事实。BANK P23兼容层与BC目标模板可能保留具名差异；没有把R2运行库能力写成当前银行生成支持。

- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/infrastructure/proto/ProtoTemplate.scala` — `ProtoTemplate`。
- `js:generators/scala-cqrs-domain/render-serialization.mjs` — `renderProto`。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。

### 类别依赖

`domain-protocol`、`domain-state`、`value-object`。这些是职责依赖，不代替真实Scala import或执行顺序验证。
