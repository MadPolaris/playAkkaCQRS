# Artifact: Aggregate Factory (聚合工厂)

**Target**: `AggregateFactoryArtifact`
**File Pattern**: `application/aggregates/factories/${Name}Factory.scala`

## 1. 核心职责
封装 Akka Cluster Sharding 的引用获取逻辑，为 Application Service 提供类型安全的入口。

## 2. 编码约束
1.  **Dependency**: 注入 `akka.cluster.sharding.typed.scaladsl.ClusterSharding`。
2.  **Methods**:
    -   `get(id: Id): EntityRef[Command]`：获取现有聚合的引用。
    -   `create(id: Id, ...): Future[...]`：如果创建逻辑复杂（如需要 `ask` 确认），在此封装。
3.  **Type Safety**: 确保使用正确的 `EntityTypeKey`。

## 3. 参考模板
```scala
class CreditBalanceAggregateFactory @Inject()(sharding: ClusterSharding) {
  def get(id: Id): EntityRef[CreditBalanceCommand] = 
    sharding.entityRefFor(CreditBalanceAggregate.TypeKey, id.toString)
}
```