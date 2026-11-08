<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/application/aggregate-repository-implementation.md; catalog 2.5.0; source-sha256 74a8c5776a86a5f3ec277e769df0d04c1d1a4210a8dc736699072e2ccb215f09. Edit the central document, not this snapshot. -->

# 聚合仓储实现

类别：`aggregate-repository-implementation`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

实现聚合引用寻址、缓存或ClusterSharding对接；不能替代聚合的业务状态。

## 输入与人工信息

使用已选Repository合同、type key、稳定ID映射以及宿主spawn/生命周期策略；不能凭局部缓存宣称故障切换。

## 生产与所有权

BC由AggregateRepositoryImplBuilder/Template读取infrastructure.repositories中的AggregateRepositoryMetadata，生成ClusterSharding/EntityRef实现；多模块该实现位于glue，独立于接口文件。

JS正式Play service的templates/aggregate-repository.mjs#repositoryTemplate从reference-layout/domain-dependencies输出一个<Name>Repository[K]具体类。接口职责与本地缓存实现职责都由同一文件承担，没有另发port trait或Impl文件；close仅标记关闭并清理映射，不停止actor或ActorSystem。legacy repository-dsl的sharding实现与retired scala-cqrs-es实现是不同路径。

按profile和物理输出记录能力，见能力矩阵（公共包 docs/artifacts/CAPABILITY-MATRIX.md）。一个文件关联两类别仍只计一个文件；源码定位不是集群部署、故障切换测试或最终批准。

## 结构与命名

BC infrastructure/repositories/aggregate/<Name>RepositoryImpl位于glue；银行JS当前本地仓储与接口合并，需明确其缓存及close语义。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

实现依赖框架，调用者仅依赖接口。资源owner掌握ActorSystem/停止职责，仓储close不能擅停共享系统。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

查找幂等、类型key/PID冲突、关闭后拒绝；集群和本地profile分别验证。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

### 第四批有限 aggregate-core 候选（当前登记，非验收）

`js-aggregate-core-candidate-v1` 对应实际入口 `scala-cqrs-aggregate-core-v1`、manifest `scala-cqrs-aggregate-core@1`。glue/infrastructure/repositories中的LocalActorRepository由调用者提供key→PersistenceId、behavior与spawn。成功且nonnull后才登记key/PID；同key复用、异key同PID拒绝、失败可重试，get/close串行且拒绝callback重入。close幂等并清缓存/拒绝lookup，但不stop Actor；不承诺deathwatch/eviction、跨JVM或分布式PID唯一。

本页登记的物理输出为 `modules/glue/src/main/scala/**/infrastructure/repositories/*.scala`。实际来源为 `generators/scala-cqrs-aggregate-core-v1/templates/repository.mjs#repositoryImplementationTemplate`；`generators/scala-cqrs-aggregate-core-v1/build-models.mjs#buildModels`。新入口组合复用的family1生产jobs和本族jobs，经 `generators/scala-cqrs-domain-kernel-v1/render.mjs#renderJobs` 与固定 `entry.mjs#aggregateSourceEntry` owner，最后由 `src/core/generate.mjs#applyEntries` 写入；复用指向原物理模板，不存在新族VO/PB复制模板。

冻结工具/来源指纹下，catalog当前实际登记20份观察（原12份样例generated；family1/family2各1份complete-plan generated及3份同样例源码generated/compiled/behavior-verified）。本页本族源码已取得同一Warehouse样例的generated、compiled、behavior-verified资格，包含实际生成的支持测试；只证明该冻结输入与最终产物字节，不推广至全profile，final-approved=0。原16条观察及74条变体引用已原样保入历史索引（公共包 docs/artifacts/evidence/capability-domain-kernel-final-history.json），原receipt路径/字节不动、不续签，其旧锁下的资格不算current；初/二/三批原验收仍保留。两类build只取得generated资格，不借源码编译/行为回执晋升。有限范围见本族合同（公共包 docs/artifacts/scala-cqrs/AGGREGATE-CORE.md）；无Bank、外部结果桥、完整四模块/服务或最终批准。

## 版本与实例

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

主要实例：bc-single / bc-multi / js-formal；legacy/retired另见上文。sharding与本地cache是不同部署合同，不是同一实现的目录别名。

- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/infrastructure/repository/AggregateRepositoryImplTemplate.scala` — `AggregateRepositoryImplTemplate`。
- `js:generators/scala-cqrs-play-service/templates/aggregate-repository.mjs` — `repositoryTemplate`，与port职责共用输出。
- `js:generators/repository-dsl/emit.mjs` — legacy sharding分支。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。

### 类别依赖

`aggregate-repository-port`、`aggregate-factory`。这些是职责依赖，不代替真实Scala import或执行顺序验证。

### 第五批有限module-seams候选

InMemoryRouteRepository是外置glue内存模拟实现，不是生成数据库adapter或持久化恢复能力；ownership=handwritten、routes为空。 该显式产品链与旧BC/legacy/ref-only变体并存，不互相继承资格。生成源码经唯一writer回锚；外置实现仅独立file级来源，不能认证任意symbol/span。完整说明见有限多模块接缝（公共包 docs/artifacts/scala-cqrs/MODULE-SEAMS.md）。

## 第六批有限 persistence-readside 候选登记

本类新增 `js-persistence-readside-candidate-v1`，由显式 `scala-cqrs-persistence-readside-v1`／`scala-cqrs-persistence-readside@1` 提供；模块为 `glue`。本段只登记实际源结构，不宣称本批已完成或已编译。

实际输出合同（可覆盖同一物理文件中的不同职责，不按合同数冒充文件数）

- `aggregate-repository-implementation`／`actor-repository-implementation`：Local Actor cache with explicit key/PersistenceId/behavior/spawn callbacks; same-key reuse, conflicting PID rejection, register only successful nonnull spawn, retry after failure, serial get/close and callback reentry rejection. close does not stop Actors; no deathwatch/eviction or distributed PID guarantee. 路径 `modules/glue/src/main/scala/**/infrastructure/repositories/*.scala`。

输入为唯一业务作者树、原七个 family2 产品及显式 persistence-readside 技术 binding；wire layout SHA 必须核对，不能自动重签。沿用 family2 kernel/glue 和唯一 writer；生成测试与独立 oracle 分开，sbt 定义存在不等任务执行。

本次登记移除旧 active evidence 引用，原24观察和旧回执在独立历史索引（公共包 docs/artifacts/evidence/capability-persistence-readside-prior-current06-history.json）保真；新观察要在生产冻结后单独采集并通过原公共验证器。本段及目录 implemented 仅表示实现存在，当前 generated/compiled/behavior/final-approved 不由本配方晋升。范围与限制见本族合同（公共包 docs/artifacts/scala-cqrs/PERSISTENCE-READSIDE.md）。

读侧文件库重放不是生产 Actor journal 耐久性；不扩张为 MySQL、任意读模型、HTTP、银行/Saga/Monarch 或架构批准。
