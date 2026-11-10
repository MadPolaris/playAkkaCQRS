<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/application/aggregate-repository-port.md; catalog 2.6.0; source-sha256 f941121a4e84e099be61581857efdfcc2baaa8813b826d6efc4592167de894dd. Edit the central document, not this snapshot. -->

# 聚合仓储接口

类别：`aggregate-repository-port`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

按业务身份取得聚合引用的应用端口，不直接表达SQL表读取。

## 输入与人工信息

身份类型和实体寻址方式必须选择：BC sharding EntityRef与当前银行本地ActorRef不是同一个运行保证。

## 生产与所有权

BC由AggregateRepositoryBuilder → AggregateRepositoryTemplate读取application.repositories，生成返回EntityRef的trait。原始模板无条件保留@ImplementedBy及实现依赖；即使多模块把接口放kernel、实现放glue，反向依赖仍在。di-poc整理后的接口不能证明raw BC模板已清除此债。

JS正式Play service的templates/aggregate-repository.mjs#repositoryTemplate从reference-layout/domain-dependencies生成<Name>Repository[K]具体类。寻址合同与本地缓存实现是一个文件的两项职责，不另发接口trait，也不提供ClusterSharding保证。legacy repository-dsl的sharding接口与retired scala-cqrs-es为独立兼容/历史路径。

各profile的形状、所有权和限制见能力矩阵（公共包 docs/artifacts/CAPABILITY-MATRIX.md）。同文件多职责不能重复计数；源码检查不等于目标编译、跨JVM验证或最终批准。

## 结构与命名

BC application/aggregates/repository/<Name>Repository为trait；JS当前<Name>Repository[K]合并本地缓存机制。相同职责采用具名profile变体，不能冒相同文件布局。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

目标是多模块接口住kernel、实现和绑定在外层，不用@ImplementedBy反向依赖glue。当前raw BC模板在多模块也保留该注解，不能仅靠移动接口文件声称已达标；JS本地具体类也不是已拆分的kernel/glue接口实现对。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

同ID返回同引用、冲突拒绝和关闭边界；跨JVM定位保证只能由实际sharding/持久化测试证明。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

### 第四批有限 aggregate-core 候选（当前登记，非验收）

`js-aggregate-core-candidate-v1` 对应实际入口 `scala-cqrs-aggregate-core-v1`、manifest `scala-cqrs-aggregate-core@1`。glue/application/aggregates中的ActorRepository[K] trait及Handle持有typed PersistenceId和ActorRef，是Actor寻址API，不是kernel业务Repository port；不含@ImplementedBy反向依赖。

本页登记的物理输出为 `modules/glue/src/main/scala/**/application/aggregates/*.scala`。实际来源为 `generators/scala-cqrs-aggregate-core-v1/templates/repository.mjs#repositoryTemplate`；`generators/scala-cqrs-aggregate-core-v1/build-models.mjs#buildModels`。新入口组合复用的family1生产jobs和本族jobs，经 `generators/scala-cqrs-domain-kernel-v1/render.mjs#renderJobs` 与固定 `entry.mjs#aggregateSourceEntry` owner，最后由 `src/core/generate.mjs#applyEntries` 写入；复用指向原物理模板，不存在新族VO/PB复制模板。

冻结工具/来源指纹下，catalog当前实际登记20份观察（原12份样例generated；family1/family2各1份complete-plan generated及3份同样例源码generated/compiled/behavior-verified）。本页本族源码已取得同一Warehouse样例的generated、compiled、behavior-verified资格，包含实际生成的支持测试；只证明该冻结输入与最终产物字节，不推广至全profile，final-approved=0。原16条观察及74条变体引用已原样保入历史索引（公共包 docs/artifacts/evidence/capability-domain-kernel-final-history.json），原receipt路径/字节不动、不续签，其旧锁下的资格不算current；初/二/三批原验收仍保留。两类build只取得generated资格，不借源码编译/行为回执晋升。有限范围见本族合同（公共包 docs/artifacts/scala-cqrs/AGGREGATE-CORE.md）；无Bank、外部结果桥、完整四模块/服务或最终批准。

## 版本与实例

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

主要实例：bc-single / bc-multi / js-formal；legacy/retired另见上文。BC目录拆分与依赖方向达标是两回事，JS本地Repository的两项职责也不表示两个文件。

- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/application/AggregateRepositoryTemplate.scala` — `AggregateRepositoryTemplate`，含当前注解债。
- `js:generators/scala-cqrs-play-service/templates/aggregate-repository.mjs` — `repositoryTemplate`。
- `js:generators/repository-dsl/emit.mjs` — legacy接口生成。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。

### 两种实际变体

BC sharding接口返回EntityRef，kernel接口/glue实现的物理拆分已存在，但raw模板保留@ImplementedBy反向依赖；root显式bind是目标及参考装配方式，不是模板已完成的普遍保证。银行JS本地形则为：

```scala
final class CreditBalanceRepository[K](
    identity: K => String,
    behavior: (K, String) => Behavior[Command],
    spawn: (String, Behavior[Command]) => ActorRef[Command])
```

它缓存Handle并在close后拒绝新查找，不等ClusterSharding，也不能停止共享ActorSystem。两种类别职责相近，但公开API、资源owner和部署保证不同。

### 类别依赖

`application-protocol`。这些是职责依赖，不代替真实Scala import或执行顺序验证。

### 第五批有限module-seams候选

本族RouteRepository是只读路由查询业务port，不是family2 Actor存储repository，也不提供真实数据库。 该显式产品链与旧BC/legacy/ref-only变体并存，不互相继承资格。生成源码经唯一writer回锚；外置实现仅独立file级来源，不能认证任意symbol/span。完整说明见有限多模块接缝（公共包 docs/artifacts/scala-cqrs/MODULE-SEAMS.md）。

## 第六批有限 persistence-readside 候选登记

本类新增 `js-persistence-readside-candidate-v1`，由显式 `scala-cqrs-persistence-readside-v1`／`scala-cqrs-persistence-readside@1` 提供；模块为 `glue`。本段只登记实际源结构，不宣称本批已完成或已编译。

实际输出合同（可覆盖同一物理文件中的不同职责，不按合同数冒充文件数）

- `aggregate-repository-port`／`actor-repository`：Actor-specific Repository[K] trait and Handle(PersistenceId, ActorRef) in glue/application; not the later-family runtime-neutral kernel business Repository port. 路径 `modules/glue/src/main/scala/**/application/aggregates/*.scala`。

输入为唯一业务作者树、原七个 family2 产品及显式 persistence-readside 技术 binding；wire layout SHA 必须核对，不能自动重签。沿用 family2 kernel/glue 和唯一 writer；生成测试与独立 oracle 分开，sbt 定义存在不等任务执行。

本次登记移除旧 active evidence 引用，原24观察和旧回执在独立历史索引（公共包 docs/artifacts/evidence/capability-persistence-readside-prior-current06-history.json）保真；新观察要在生产冻结后单独采集并通过原公共验证器。本段及目录 implemented 仅表示实现存在，当前 generated/compiled/behavior/final-approved 不由本配方晋升。范围与限制见本族合同（公共包 docs/artifacts/scala-cqrs/PERSISTENCE-READSIDE.md）。

读侧文件库重放不是生产 Actor journal 耐久性；不扩张为 MySQL、任意读模型、HTTP、银行/Saga/Monarch 或架构批准。
