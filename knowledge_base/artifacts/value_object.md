<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/domain/value-object.md; catalog 2.1.0; source-sha256 09ffd2a0e631f040189d7ec458c624c53345c263b7ad615c586c549f12dc6cb2. Edit the central document, not this snapshot. -->

# 值对象与签名类型

类别：`value-object`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

表达领域值、身份或命名record，不承担实体生命周期。kernel-vo是相同值对象被port签名闭包带入kernel的放置变体，不能复制一套同名定义。

## 输入与人工信息

输入是明确的字段、类型、可空性、默认值和领域语义。ID如何等同、货币精度、日期含义等必须由知识或技术绑定决定；不能按第一个字段猜主键。

## 生产与所有权

BC由ValueObjectBuilder → ValueObjectTemplate读取SystemMetadata.domain.valueObjects；多模块的VO放置由targetModuleOf决定，放到kernel不证明端口递归签名闭包已检查。

JS正式值类型由render-values.mjs#renderValues消费reference-layout、exact-value-types，输出普通record/nominal及带精度约束的exact类型；naming.mjs#typeName仅解析类型名称。legacy valueobject-dsl另提供case class、Play JSON格式器和选定helper（含>22字段分支），retired scala-cqrs-es是历史VO模板，不能混算当前正式目标支持。

各profile的生成能力、放置和生命周期见能力矩阵（公共包 docs/artifacts/CAPABILITY-MATRIX.md）。生成文件经公共更新通道维护；生产源码的存在不表示本轮目标生成、跨模块编译、往返测试或最终批准。

## 结构与命名

BC使用domain/values下的case class与伴生对象；大于22字段时的显式格式器按模板支持，BC单项目app与多模块kernel/src/main/scala是其放置变体。JS正式exact值及普通record同样位于domain/values，但当前正式目标为单项目，不由BC多模块路径推定JS也已支持kernel布局。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

port所有签名可达的自有值类型必须处于接口可依赖的模块；领域VO不得为了JSON/PB便利依赖外层实现。包装器、DTO与数据库行不是因为字段相同就同一类型。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

检查字段/顺序/精确表示，JSON与PB往返、>22字段、关键字/可选字段与默认值反例；生成前后不能丢失已有字段。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

## 版本与实例

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

主要实例：bc-single / bc-multi / js-formal；legacy/retired模板见上文。BC的模块放置不自动成为JS能力，共享subproducer也不代表额外独立目标已验收。

- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/domain/ValueObjectTemplate.scala` — `ValueObjectTemplate`。
- `js:generators/scala-cqrs-domain/render-values.mjs` — `renderValues`，实际值类型输出。
- `js:generators/valueobject-dsl/render.mjs` — legacy格式器/helper输出。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。

### 第二批独立模块候选

`scala-cqrs-modular-candidate-v1`新增独立Parcel参考及纯目标合同，属于reference-only，不是现役生成入口。Seven record values and an explicit closed error sum; ten production files in the whole reference, not one file per value. 结构、兼容差异及资格见目标合同（公共包 docs/artifacts/scala-cqrs/TARGET-PROFILES.md）；真实编译/行为结果单独记录，不把参考升级为生成器或最终金样。
