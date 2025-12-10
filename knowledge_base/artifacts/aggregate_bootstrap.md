# Artifact: Aggregate Bootstrap (聚合引导)

**Target**: `AggregateBootstrapArtifact`
**File Pattern**: `infrastructure/bootstrap/${Name}Bootstrap.scala`

## 1. 核心职责
在系统启动时初始化聚合根的 **Cluster Sharding** 分片。

## 2. 编码约束
1.  **Trait Definition**: 定义为 `trait ${Name}Bootstrap`。
2.  **Initialization**:
    -   方法名推荐 `init${Name}(sharding: ClusterSharding)`。
    -   使用 `sharding.init(Entity(TypeKey) { context => ... })`。
3.  **Behavior Creation**:
    -   在回调中创建聚合 Behavior：`${Name}Behavior(PersistenceId(...))`。
4.  **Persistence Config**:
    -   **必须**配置 `.withRetention(RetentionCriteria.snapshotEvery(100, 3))`。
    -   **必须**配置 `.onPersistFailure(SupervisorStrategy.restartWithBackoff(...))`。
5.  **Adapters**:
    -   在此处注册 `EventAdapter` 和 `SnapshotAdapter`。

## 3. 参考模板
```scala
trait CreditBalanceBootstrap {
  def initCreditBalance(sharding: ClusterSharding): Unit = {
    sharding.init(Entity(CreditBalanceAggregate.TypeKey) { context =>
      Behaviors.setup { ctx =>
        EventSourcedBehavior(...)
          .withRetention(...)
          .eventAdapter(...)
      }
    })
  }
}
```