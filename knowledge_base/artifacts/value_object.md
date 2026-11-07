<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/domain/value-object.md; catalog 1.0.0; source-sha256 a46e056fe0ef57b2354e9749181ac3cb3f60f33d7b9aa9172bb0f96a19170a53. Edit the central document, not this snapshot. -->

# 值对象与签名类型

类别：`value-object`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

表达领域值、身份或命名record，不承担实体生命周期。kernel-vo是相同值对象被port签名闭包带入kernel的放置变体，不能复制一套同名定义。

## 输入与人工信息

输入是明确的字段、类型、可空性、默认值和领域语义。ID如何等同、货币精度、日期含义等必须由知识或技术绑定决定；不能按第一个字段猜主键。

## 生产与所有权

由列出的生产者/实现提供。BC的Scala结构与JS最终写入者必须一起判断；同文件后续JS覆写时，最终台账行归最后写入者。当前银行兼容profile只按实际已有产物标识，不由旧文档推定能力。

目录状态：`implemented`；所有权：`generated`。生成文件通过公共生成/更新通道维护；独立手写实现不是生成结果的可编辑副本。

## 结构与命名

BC使用domain/values下的case class与伴生对象；大于22字段时的显式格式器按模板支持。JS exact值及普通record同样位于domain/values，单模块app与多模块kernel/src/main/scala只改变根路径。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

port所有签名可达的自有值类型必须处于接口可依赖的模块；领域VO不得为了JSON/PB便利依赖外层实现。包装器、DTO与数据库行不是因为字段相同就同一类型。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

检查字段/顺序/精确表示，JSON与PB往返、>22字段、关键字/可选字段与默认值反例；生成前后不能丢失已有字段。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

## 版本与实例

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

适用：bc-single / bc-multi / js-formal。单模块和多模块是放置/装配变体，不是两套业务事实。BANK P23兼容层与BC目标模板可能保留具名差异；没有把R2运行库能力写成当前银行生成支持。

- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/domain/ValueObjectTemplate.scala` — `ValueObjectTemplate`。
- `js:generators/scala-cqrs-domain/naming.mjs` — `typeName`。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。
