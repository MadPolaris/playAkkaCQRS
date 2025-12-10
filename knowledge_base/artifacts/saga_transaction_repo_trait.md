# Artifact: Saga Transaction Repository Interface

**Target**: `SagaTransactionRepositoryTraitArtifact`
**File Pattern**: `application/services/transactor/${SagaName}TransactionRepository.scala`

## 1. 核心职责
允许应用服务获取 Saga Transactor (Facade Actor) 的引用。

## 2. 编码约束
1.  **Return Type**: 返回 `ActorRef[${SagaName}Transactor.Command]`。
2.  **Method**: `findTransactionById(transactionId: Id)`。
3.  **Implementation**: 绑定到 `infrastructure` 层的实现。

## 3. 参考模板
```scala
trait MoneyTransferTransactionRepository {
  def findTransactionById(id: Id): ActorRef[MoneyTransferTransactionCommand]
}
```