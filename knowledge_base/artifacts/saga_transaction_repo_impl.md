# Artifact: Saga Transaction Repository Implementation

**Target**: `SagaTransactionRepositoryImplArtifact`
**File Pattern**: `infrastructure/repositories/service/${SagaName}TransactionRepositoryImpl.scala`

## 1. 核心职责
实现 Repository 接口，动态创建或获取 Transactor Actor。

## 2. 编码约束
1.  **Logic**:
    -   获取 `SagaTransactionCoordinator` 的 EntityRef。
    -   创建一个 **Ephemeral Actor** (或者 Spawn 一个名为 `${SagaName}TransactorBehaviors` 的 Actor)。
    -   **注意**: 这里的实现通常是 `system.systemActorOf(Behaviors.setup..., name)`，为每个 Transaction ID 创建一个临时的 Transactor Actor 来处理。
2.  **Dependencies**: 注入 `ClusterSharding` 和 `Repository` (用于构造 Participant)。

## 3. 参考模板
```scala
class MoneyTransferTransactionRepositoryImpl @Inject()(sharding: ClusterSharding, ...) {
  def findTransactionById(id: Id): ActorRef[...] = {
    val coordinator = sharding.entityRefFor(...)
    // Spawn a behavior that knows how to talk to coordinator
    system.systemActorOf(MoneyTransferSagaTransactorBehaviors.apply(coordinator, ...), s"transactor-$id")
  }
}
```