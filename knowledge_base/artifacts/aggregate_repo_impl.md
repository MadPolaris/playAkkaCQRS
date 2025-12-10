#### B. `aggregate_repository_impl.md` (拆分自 repository_impl.md)
```markdown
# Artifact: Aggregate Repository Implementation

**Target**: `AggregateRepositoryImplArtifact`
**File Pattern**: `infrastructure/repositories/aggregate/${Name}Impl.scala`

## 1. 核心职责
实现聚合仓库接口，连接 Akka Cluster Sharding。

## 2. 编码约束
1.  **Dependencies**: 注入 `ClusterSharding`。
2.  **Logic**: 使用 `sharding.entityRefFor(TypeKey, id)` 获取引用。
3.  **Sync**: 获取 EntityRef 是同步操作，不需要 Future。

## 3. 参考模板
```scala
class CreditBalanceRepositoryImpl @Inject()(sharding: ClusterSharding) extends CreditBalanceRepository {
  def find(id: Id) = sharding.entityRefFor(CreditBalanceAggregate.TypeKey, id.toString)
}
```