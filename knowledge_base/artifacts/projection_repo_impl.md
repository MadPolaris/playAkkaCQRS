<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/projection/projection-repository-implementation.md; catalog 2.3.0; source-sha256 d7978d70c3eb1cad3978fe09df213948731fc6780620fb036924c515f66b0e36. Edit the central document, not this snapshot. -->

# 读侧仓储与事务实现

类别：`projection-repository-implementation`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

实现视图的SQL/Mongo/Graph存储和事务，保持namespace/身份/更新契约。不决定未声明的业务owner或金融状态。

## 输入与人工信息

数据库方言、表/索引、映射、事务策略、参数化值和查询权限由技术及业务绑定分层提供。

## 生产与所有权

- `bc-single` / `bc-multi`：BC `ProjectionRepositoryImplBuilder/Template` 按存储分支生成实现；multi 的模块位置不保证驱动和资源供应已闭合。
- `js-formal`：`renderJournalRepository` 生成具体 DataSource/JDBC 仓储与路径依赖 Session，和 journal projection 的事务合同组合。
- `js-legacy`：`viewrepo` 生成 SQL/Mongo/graph/vector 实现或委托；`js-retired`：退役 projection emitter 是独立来源。
- legacy graph 的 `mergeLinkTolerant` 仍为 `???`，graph/vector 依赖手写存储/driver。不同 profile 的同名 Repository 不代表 session、事务或存储 ABI 可互换，更不代表完整 graph 行为。

逐 profile 的生命周期、生产链与证据层级见派生能力矩阵（公共包 docs/artifacts/CAPABILITY-MATRIX.md）。可用源码、生成接线、目标编译、行为验证和最终批准不是同一声明。

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

本页按上述 profile 区分当前原生、保留/退役路径与手写参考；不跨 profile 传播实现或验收结论。完整来源 revision、摘要和生产链由目录、派生矩阵及 `sources.lock.json` 记录，本页不另抄暂定统计。

### 类别依赖

`projection-repository-port`、`projection-schema`、`database-driver-adapter`。这些是职责依赖，不代替真实Scala import或执行顺序验证。

### 第二批独立模块候选

`scala-cqrs-modular-candidate-v1`新增独立Parcel参考及纯目标合同，属于reference-only，不是现役生成入口。Immutable supplied route-map adapter; not JDBC, event projection or transaction acceptance. 结构、兼容差异及资格见目标合同（公共包 docs/artifacts/scala-cqrs/TARGET-PROFILES.md）；真实编译/行为结果单独记录，不把参考升级为生成器或最终金样。
