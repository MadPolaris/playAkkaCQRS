<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/projection/projection-repository-implementation.md; catalog 1.0.0; source-sha256 895a4db3ed3bb3f8703057f88c2edb33b67029ae1f6303a23d4b103a4eb7a2da. Edit the central document, not this snapshot. -->

# 读侧仓储与事务实现

类别：`projection-repository-implementation`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

实现视图的SQL/Mongo/Graph存储和事务，保持namespace/身份/更新契约。不决定未声明的业务owner或金融状态。

## 输入与人工信息

数据库方言、表/索引、映射、事务策略、参数化值和查询权限由技术及业务绑定分层提供。

## 生产与所有权

由列出的生产者/实现提供。BC的Scala结构与JS最终写入者必须一起判断；同文件后续JS覆写时，最终台账行归最后写入者。当前银行兼容profile只按实际已有产物标识，不由旧文档推定能力。

目录状态：`implemented`；所有权：`generated`。生成文件通过公共生成/更新通道维护；独立手写实现不是生成结果的可编辑副本。

## 结构与命名

BC infrastructure/repositories/projection，通常glue，含特定业务适配的Graph实现可在ext；JS生成同Connection Session Repository与checkpoint操作。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

只通过接口被调用；连接池/driver是I/O机制，必须保持同一事务session，不能在内部再开独立事务破坏原子性。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

事务回滚/约束冲突、复合键、SQL注入边界、并发与重放幂等；scope/权限要用反例验证。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

## 版本与实例

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

适用：bc-single / bc-multi / js-formal。单模块和多模块是放置/装配变体，不是两套业务事实。BANK P23兼容层与BC目标模板可能保留具名差异；没有把R2运行库能力写成当前银行生成支持。

- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/infrastructure/repository/ProjectionRepositoryImplTemplate.scala` — `ProjectionRepositoryImplTemplate`。
- `js:generators/scala-cqrs-play-service/render-journal-repository.mjs` — `renderJournalRepository`。
- `js:generators/projection-dsl/viewrepo.mjs` — `generateViewRepos`。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。

### 类别依赖

`projection-repository-port`、`projection-schema`、`database-driver-adapter`。这些是职责依赖，不代替真实Scala import或执行顺序验证。
