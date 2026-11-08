<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/persistence/protobuf-schema.md; catalog 2.3.0; source-sha256 789cb17c66999caf2c19fbae8dd97055431b455abb659b6eea65e4d92048b4d5. Edit the central document, not this snapshot. -->

# Protobuf线格式定义

类别：`protobuf-schema`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

定义跨进程/持久化wire消息与字段号码，不与领域事件或Scala类名混成同一身份。

## 输入与人工信息

输入包括消息变体、有序字段、可选性、schema版本和兼容策略。稳定字段号是wire演进义务；JS正式路径按字段顺序派生fN及号码，不提供作者独立编号的普遍保证。删除、重用号码或重排字段都不是普通命名整理。

## 生产与所有权

BC由AggregateRootProtoBuilder → ProtoTemplate消费聚合及共享可达VO，writeProto选择单项目app/protobuf或多模块glue/src/main/protobuf。

JS正式serializationModel → render-serialization.mjs#renderProto按包输出<package_underscored>_domain.proto，覆盖状态、事件及exact/nominal/集合包装。fN字段及号码由有序字段机械分配，可选存在标记影响偏移；并非读取作者逐个指定的稳定字段号。字段重排因此是兼容问题。legacy proto-dsl的聚合/共享消息与retired scala-cqrs-es为独立历史路径。

各profile的schema生成能力见能力矩阵（公共包 docs/artifacts/CAPABILITY-MATRIX.md）。ScalaPB managed sources是后续构建产物，不是该源码扫描观察到的生成或编译结果；本轮不宣称旧bytes验证或最终批准。

## 结构与命名

单项目app/protobuf与多模块glue/src/main/protobuf；生成ScalaPB类型属于构建期managed sources，不是truthsrc直接输出的相同库存。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

由converter、serializer和adapter消费；kernel领域事件不直接依赖glue PO。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

PB往返、旧bytes兼容、缺required业务字段拒绝与枚举分支。proto3线格式可空不等于领域语义可空，读侧容忍须专门decoder而非改共享strict converter。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

### 第三批有限 domain-kernel 候选

`js-domain-kernel-candidate-v1`（实际入口 `scala-cqrs-domain-kernel-v1`，manifest `scala-cqrs-domain-kernel:1`）为显式选择的有限实现；不切换旧入口或银行消费。Actual .proto source; explicit message identity/presence. Managed ScalaPB source and byte roundtrip require separate execution.

实际来源为 `generators/scala-cqrs-domain-kernel-v1/templates/protobuf.mjs#protobufTemplate`；结构经 `buildModels`／`serializationModel`、有限 `render` 与逐职责模板，`sourceEntry` 记录来源，最终写入仍由 `src/core/generate.mjs#applyEntries` 承担。共享输出不重复算文件；完整范围见有限合同（公共包 docs/artifacts/scala-cqrs/DOMAIN-KERNEL.md）。

`implemented` 只表示上述代码确实存在。此登记阶段没有刷新观察或 source lock／coverage／矩阵，也没有编译、独立行为或最终审定通过声明；`approval:null`。真实证据必须绑定同一输入、全套最终产物字节及冻结工具指纹。 既有第二批14项 reference-only 资格不变；稳定外置 source 映射不恢复 inline `@knowledge`。

### 第四批有限 aggregate-core 候选（当前登记，非验收）

`js-aggregate-core-candidate-v1` 对应实际入口 `scala-cqrs-aggregate-core-v1`、manifest `scala-cqrs-aggregate-core@1`。沿原family1物理PB模板生成glue中的真实proto，复用显式PB身份与容器presence；managed ScalaPB源码是后续编译产物，不加入24-entry生产计划凑数。

本页登记的物理输出为 `modules/glue/src/main/protobuf/*_domain.proto`。实际来源为 `generators/scala-cqrs-domain-kernel-v1/templates/protobuf.mjs#protobufTemplate`；`generators/scala-cqrs-domain-kernel-v1/build-models.mjs#buildModels`。新入口组合复用的family1生产jobs和本族jobs，经 `generators/scala-cqrs-domain-kernel-v1/render.mjs#renderJobs` 与固定 `entry.mjs#aggregateSourceEntry` owner，最后由 `src/core/generate.mjs#applyEntries` 写入；复用指向原物理模板，不存在新族VO/PB复制模板。

`implemented` 仅表示上述源码存在，source仍活跃，未声称最终核验。当前无generation/compiled/behavior/final-approved观察；全量strict比较、冻结后锁与真实双lane由主集成负责。原16条观察及74条变体引用已原样保入历史索引（公共包 docs/artifacts/evidence/capability-domain-kernel-final-history.json），原receipt路径/字节不动、不续签，其旧锁下的资格不算current；初/二/三批原验收仍保留。构建只可取得generation资格。有限范围见本族合同（公共包 docs/artifacts/scala-cqrs/AGGREGATE-CORE.md）；无Bank、外部结果桥、完整四模块/服务或最终批准。

## 版本与实例

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

主要实例：bc-single / bc-multi / js-formal；legacy/retired另见上文。字段顺序、wire号码与后续ScalaPB构建证据分别核对，不把目录中的schema能力当作旧bytes兼容或R2支持。

- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/infrastructure/proto/ProtoTemplate.scala` — `ProtoTemplate`。
- `js:generators/scala-cqrs-domain/render-serialization.mjs` — `renderProto`。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。

### 类别依赖

`domain-protocol`、`domain-state`、`value-object`。这些是职责依赖，不代替真实Scala import或执行顺序验证。
