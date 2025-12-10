# Artifact: Saga Step Serializer

**Target**: `SagaTransactionStepSerializerArtifact`
**File Pattern**: `infrastructure/persistence/${SagaName}StepSerializer.scala`

## 1. 核心职责
实现 **"Payload Multiplexing" (载荷多路复用)** 模式。将具体的业务对象与通用的 Saga 引擎解耦。

## 2. 编码约束
1.  **Serialize**:
    -   接收 `SagaTransactionStep[E, R]`。
    -   提取其中的 `Participant` (业务对象)。
    -   将其转换为对应的业务 Proto (`XxxParticipantPO`)。
    -   序列化为 `ByteString`。
    -   包装进通用的 `SagaParticipantPO(typeName, payload)`。
2.  **Deserialize**:
    -   接收通用 `SagaParticipantPO`。
    -   根据 `typeName` 字段，使用 `parseFrom(payload)` 还原回具体的业务 Participant 对象。
## 3. 参考模板
```scala
case class SagaTransactionStepSerializer(repository: CreditBalanceRepository, ec: ExecutionContext) extends AbsSagaTransactionStepSerializer {

  implicit val executionContext: ExecutionContext = ec

  override def identifier: Int = 1234

  override def serializeSagaTransactionStep(step: SagaTransactionStep[_, _]): SagaTransactionStepPO = {
    val (typeName, payloadBytes) = step.participant match {
      case FromAccountParticipant(fromUserId, amount, _) =>
        val specificPO = FromAccountParticipantPO(
          fromUserId.toString,
          Some(MoneyPO(amount.amount.doubleValue, amount.currency.getCurrencyCode))
        )
        // 返回：(类型标记, 二进制数据)
        ("FromAccountParticipantPO", ByteString.copyFrom(specificPO.toByteArray))

      case ToAccountParticipant(toUserId, amount, _) =>
        val specificPO = ToAccountParticipantPO(
          toUserId.toString,
          Some(MoneyPO(amount.amount.doubleValue, amount.currency.getCurrencyCode))
        )
        ("ToAccountParticipantPO", ByteString.copyFrom(specificPO.toByteArray))

      case _ => throw new IllegalArgumentException("Unknown participant type")
    }
    val genericParticipantPO = SagaParticipantPO(
      typeName = typeName,
      payload = payloadBytes
    )
    writeSagaParticipantPO(step, genericParticipantPO)
  }


  override def deserializeSagaTransactionStep(stepPO: SagaTransactionStepPO): SagaTransactionStep[iMadzError, String] = {
    val genericParticipant = stepPO.participant.getOrElse(throw new IllegalArgumentException("Missing participant"))

    // 1. 根据 type_name 决定如何解析 payload
    val participant: SagaParticipant[iMadzError, String] = genericParticipant.typeName match {
      case "FromAccountParticipantPO" =>
        // 解析具体的业务 Proto
        val specificPO = FromAccountParticipantPO.parseFrom(genericParticipant.payload.toByteArray)
        // 转换回 Scala 对象
        FromAccountParticipant(Id.of(specificPO.fromUserId), Money(BigDecimal(specificPO.getAmount.amount), Currency.getInstance(specificPO.getAmount.currency)), repository)(ec)

      case "ToAccountParticipantPO" =>
        val specificPO = ToAccountParticipantPO.parseFrom(genericParticipant.payload.toByteArray)
        ToAccountParticipant(Id.of(specificPO.toUserId), Money(BigDecimal(specificPO.getAmount.amount), Currency.getInstance(specificPO.getAmount.currency)), repository)(ec)

      case _ => throw new IllegalArgumentException(s"Unknown type: ${genericParticipant.typeName}")
    }

    readSagaTransactionStep(stepPO, participant)
  }

}

```