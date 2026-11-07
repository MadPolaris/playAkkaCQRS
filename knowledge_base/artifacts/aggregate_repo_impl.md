<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/application/aggregate-repository-implementation.md; catalog 2.2.0; source-sha256 9bc8d800173f640c8ae52a190ab3a3908eac5fe1d715f7102987c58a49b7018d. Edit the central document, not this snapshot. -->

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

## 版本与实例

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

主要实例：bc-single / bc-multi / js-formal；legacy/retired另见上文。sharding与本地cache是不同部署合同，不是同一实现的目录别名。

- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/infrastructure/repository/AggregateRepositoryImplTemplate.scala` — `AggregateRepositoryImplTemplate`。
- `js:generators/scala-cqrs-play-service/templates/aggregate-repository.mjs` — `repositoryTemplate`，与port职责共用输出。
- `js:generators/repository-dsl/emit.mjs` — legacy sharding分支。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。

### 类别依赖

`aggregate-repository-port`、`aggregate-factory`。这些是职责依赖，不代替真实Scala import或执行顺序验证。
