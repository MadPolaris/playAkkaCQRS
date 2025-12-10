# Artifact: Saga Transactor (事务门面)

**Target**: `SagaTransactorArtifact`
**File Pattern**: `application/services/transactor/${SagaName}Transactor.scala`

## 1. 核心职责
定义 Saga 事务的启动入口和协议。它是客户端眼中的 "Transaction Manager"。

## 2. 编码约束
1.  **Facade Only**: 这是一个门面 Actor。不要在这里实现复杂的状态流转。
2.  **Protocol**:
    -   `Command`: 通常是 `InitiateTransaction(...)`。
    -   `Reply`: `TransactionResultConfirmation`。
3.  **Logic**:
    -   不要在此处写逻辑，逻辑在 `...Behaviors.scala` 中。
    -   这里主要负责定义 `createTransactionSteps` 方法（蓝图绘制）。

## 3. 参考模板
```scala
object MoneyTransferSagaTransactor {
  // 1. 定义 Command (业务语义)
  sealed trait Command
  case class InitiateTransfer(...) extends Command
  
  // 2. 定义编排逻辑 (The Plan)
  def createSteps(...): List[SagaTransactionStep[...]] = {
    val partA = new AccountParticipant(...)
    val partB = new AccountParticipant(...)
    List(
      SagaTransactionStep("step1", PreparePhase, partA),
      SagaTransactionStep("step2", PreparePhase, partB)
      SagaTransactionStep("step3", CommitPhase, partA)
      SagaTransactionStep("step4", CommitPhase, partB)
      SagaTransactionStep("step5", CompensatePhase, partA)
      SagaTransactionStep("step6", CompensatePhase, partB)
    )
  }
}
```