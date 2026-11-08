<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/domain/value-object.md; catalog 2.3.0; source-sha256 83fd5b5ea5b5b86537a6272e2633778d81a9c676127b973e8fb29c7221564d25. Edit the central document, not this snapshot. -->

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

### 第三批有限 domain-kernel 候选

`js-domain-kernel-candidate-v1`（实际入口 `scala-cqrs-domain-kernel-v1`，manifest `scala-cqrs-domain-kernel:1`）为显式选择的有限实现；不切换旧入口或银行消费。One definition per declared nominal/record/exact domain/wire/PO value; kernel-only dependencies.

实际来源为 `generators/scala-cqrs-domain-kernel-v1/templates/values.mjs#valueTemplate`；结构经 `buildModels`／`serializationModel`、有限 `render` 与逐职责模板，`sourceEntry` 记录来源，最终写入仍由 `src/core/generate.mjs#applyEntries` 承担。共享输出不重复算文件；完整范围见有限合同（公共包 docs/artifacts/scala-cqrs/DOMAIN-KERNEL.md）。

`implemented` 只表示上述代码确实存在。此登记阶段没有刷新观察或 source lock／coverage／矩阵，也没有编译、独立行为或最终审定通过声明；`approval:null`。真实证据必须绑定同一输入、全套最终产物字节及冻结工具指纹。 既有第二批14项 reference-only 资格不变；稳定外置 source 映射不恢复 inline `@knowledge`。

### 第四批有限 aggregate-core 候选（当前登记，非验收）

`js-aggregate-core-candidate-v1` 对应实际入口 `scala-cqrs-aggregate-core-v1`、manifest `scala-cqrs-aggregate-core@1`。沿原family1模板生成单份名义、record、exact及显式wire/PO值；kernel只依赖自身值与Scala标准库，不复制所谓新族VO。

本页登记的物理输出为 `modules/kernel/src/main/scala/**/domain/values/*.scala`。实际来源为 `generators/scala-cqrs-domain-kernel-v1/templates/values.mjs#valueTemplate`；`generators/scala-cqrs-domain-kernel-v1/build-models.mjs#buildModels`。新入口组合复用的family1生产jobs和本族jobs，经 `generators/scala-cqrs-domain-kernel-v1/render.mjs#renderJobs` 与固定 `entry.mjs#aggregateSourceEntry` owner，最后由 `src/core/generate.mjs#applyEntries` 写入；复用指向原物理模板，不存在新族VO/PB复制模板。

冻结工具/来源指纹下，catalog当前实际登记20份观察（原12份样例generated；family1/family2各1份complete-plan generated及3份同样例源码generated/compiled/behavior-verified）。本页本族源码已取得同一Warehouse样例的generated、compiled、behavior-verified资格，包含实际生成的支持测试；只证明该冻结输入与最终产物字节，不推广至全profile，final-approved=0。原16条观察及74条变体引用已原样保入历史索引（公共包 docs/artifacts/evidence/capability-domain-kernel-final-history.json），原receipt路径/字节不动、不续签，其旧锁下的资格不算current；初/二/三批原验收仍保留。两类build只取得generated资格，不借源码编译/行为回执晋升。有限范围见本族合同（公共包 docs/artifacts/scala-cqrs/AGGREGATE-CORE.md）；无Bank、外部结果桥、完整四模块/服务或最终批准。

## 版本与实例

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

主要实例：bc-single / bc-multi / js-formal；legacy/retired模板见上文。BC的模块放置不自动成为JS能力，共享subproducer也不代表额外独立目标已验收。

- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/domain/ValueObjectTemplate.scala` — `ValueObjectTemplate`。
- `js:generators/scala-cqrs-domain/render-values.mjs` — `renderValues`，实际值类型输出。
- `js:generators/valueobject-dsl/render.mjs` — legacy格式器/helper输出。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。

### 第二批独立模块候选

`scala-cqrs-modular-candidate-v1`新增独立Parcel参考及纯目标合同，属于reference-only，不是现役生成入口。Seven record values and an explicit closed error sum; ten production files in the whole reference, not one file per value. 结构、兼容差异及资格见目标合同（公共包 docs/artifacts/scala-cqrs/TARGET-PROFILES.md）；真实编译/行为结果单独记录，不把参考升级为生成器或最终金样。
