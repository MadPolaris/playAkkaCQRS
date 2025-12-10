# Artifact: Saga Transactor Behaviors (事务逻辑)

**Target**: `SagaTransactorBehaviorArtifact`
**File Pattern**: `application/services/transactor/${SagaName}Behaviors.scala`

## 1. 核心职责
处理 `Initiate` 命令，驱动 `SagaTransactionCoordinator`。

## 2. 编码约束
1.  **Interaction Pattern**:
    -   接收 `Initiate` 命令。
    -   调用 `Transactor.createTransactionSteps` 构建步骤列表。
    -   向 `SagaTransactionCoordinator` 发送 `StartTransaction`。
    -   使用 **Ask Pattern** 或 **Ephemeral Child Actor** 等待 Coordinator 的最终结果。
    -   将结果转换为业务 Reply 返回给客户端。
2.  **Dependencies**: 依赖 `SagaTransactionCoordinator` 和 `AggregateRepository`（用于构建 Participants）
3. ## 3. 参考模板
```scala
object MoneyTransferSagaTransactorBehaviors {
  implicit val askTimeout: Timeout = Timeout(30 seconds)

  def apply(context: ActorContext[MoneyTransferTransactionCommand], coordinator: EntityRef[SagaTransactionCoordinator.Command], repository: CreditBalanceRepository)(implicit ec: ExecutionContext, scheduler: Scheduler): Behavior[MoneyTransferTransactionCommand] =
    Behaviors.receiveMessage {
      case InitiateMoneyTransferTransaction(fromUserId, toUserId, amount, replyTo) =>
        val transactionId = Id.gen.toString
        val steps = createTransactionSteps(fromUserId, toUserId, amount, repository)

        coordinator.ask[TransactionResult](intermediateReplyTo =>
            SagaTransactionCoordinator.StartTransaction[iMadzError, String](transactionId, steps, Some(intermediateReplyTo)))
          .mapTo[TransactionResult]
          .foreach(context.self ! UpdateMoneyTransferTransactionStatus(Id.of(transactionId), _, replyTo))

        Behaviors.same

      case UpdateMoneyTransferTransactionStatus(id, transactionResponse, replyTo) =>
        if (transactionResponse.successful) {
          replyTo ! TransactionResultConfirmation(id, None, transactionResponse.tracingSteps)
        } else {
          replyTo ! TransactionResultConfirmation(id, Some(transactionResponse.failReason), transactionResponse.tracingSteps)
        }
        Behaviors.stopped
    }
}
```