# Artifact: Saga Coordinator Bootstrap (Saga 引导)

**Target**: `SagaBootstrapArtifact`
**File Pattern**: `infrastructure/bootstrap/${SagaName}CoordinatorBootstrap.scala`

## 1. 核心职责
初始化业务专属的 Saga 协调器集群分片。

## 2. 编码约束
1.  **Serializer**: 必须实例化业务专属的 `${SagaName}StepSerializer`。
2.  **Sharding Init**:
    -   初始化 `SagaTransactionCoordinator`。
    -   **关键**: 将 `stepSerializer` 传递给 Coordinator 的工厂方法。这是 Saga 引擎能解析业务步骤的关键。
    -   `SagaTransactionCoordinator.apply(..., stepExecutorFactory)`。

## 3. 参考模板
```scala
trait MoneyTransferSagaCoordinatorBootstrap {
  def initSagaCoordinator(sharding: ClusterSharding, repo: CreditBalanceRepository): Unit = {
    val serializer = new MoneyTransferSagaStepSerializer(repo)
    sharding.init(Entity(SagaTransactionCoordinator.TypeKey) { ctx =>
       SagaTransactionCoordinator(..., serializer)
    })
  }
}
```