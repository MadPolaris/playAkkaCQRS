# Saga 开发者逐步指南

使用 Saga 框架开发分布式事务需要一种全局视角，并严格遵守 **“三位一体”原则**：即 **Protobuf Schema**、**Proto Converter** 与 **Scala 领域模型** 之间的同步。

---

## 第 1 步：定义 Protobuf Schema (事实源)

所有 Saga 参与者 (Participant) 都必须是可序列化的，以便持久化事务状态。首先在 `.proto` 文件中定义参与者的数据结构。

```proto
// app/protobuf/saga_participant.proto
syntax = "proto3";
package net.imadz.infrastructure.proto;

message MyParticipantPO {
  string some_id = 1;
  int64 amount = 2;
}
```

## 第 2 步：实现 Proto Converter (转换器)

Saga 引擎使用这些转换器将您的 Scala 对象转换为 Protobuf 消息（反之亦然），用于持久化存储和集群间的通信。

```scala
// app/net/imadz/infrastructure/persistence/converters/SagaProtoConverters.scala
trait SagaProtoConverters extends ProtoConverterBase {
  case class MyParticipantConv()(implicit ec: ExecutionContext) 
    extends ProtoConverter[MyParticipant, MyParticipantPO] {
    
    override def toProto(d: MyParticipant): MyParticipantPO = 
      MyParticipantPO(someId = d.id.toString, amount = d.amount)

    override def fromProto(p: MyParticipantPO): MyParticipant = 
      MyParticipant(id = Id.of(p.someId), amount = p.amount)
  }
}
```

## 第 3 步：定义领域协议与上下文 (Context)

定义您的 Transactor 聚合 (Aggregate) 的命令，以及用于向参与者注入依赖项的共享 `Context`。

```scala
// 协议
object MySagaProtocol {
  sealed trait Command extends CborSerializable
  case class StartSaga(id: Id, ...) extends Command
  // 用于接收 SagaCoordinator 结果的回调
  case class HandleResult(res: TransactionResult) extends Command 
}

// 上下文
case class MySagaContext(repository: MyRepository, apiClient: MyClient)
```

## 第 4 步：实现 Saga 参与者 (Participant)

实现 `SagaParticipant` 特性。这是您编写 **准备 (Prepare)**、**提交 (Commit)** 和 **补偿 (Compensate)** 业务逻辑的地方。

```scala
case class MyParticipant(id: Id, amount: Long)(implicit ec: ExecutionContext) 
  extends SagaParticipant[MyError, MyResult, MySagaContext] {

  override def doPrepare(txId: String, ctx: MySagaContext, traceId: String) = {
    ctx.repository.reserve(id, amount).map(...)
  }
  
  override def doCommit(txId: String, ctx: MySagaContext, traceId: String) = ...
  override def doCompensate(txId: String, ctx: MySagaContext, traceId: String) = ...

  // 将故障分类为：可重试 (Retryable) 与 不可重试 (Non-Retryable)
  override protected def customClassification = {
    case e: TimeoutException => RetryableFailure(e.getMessage)
  }
}
```

## 第 5 步：在 Transactor 中进行编排

组装步骤并使用 `SagaTransactionCoordinator` 启动事务。

```scala
val steps = List(
  SagaTransactionStep("step-1", PreparePhase, myPart1, maxRetries = 3),
  SagaTransactionStep("step-2", CommitPhase, myPart1, maxRetries = 3),
  SagaTransactionStep("undo-1", CompensatePhase, myPart1, maxRetries = 5)
)

context.ask(coordinator, (ref: ActorRef[TransactionResult]) => 
  StartTransaction(txId, steps, Some(ref))
) {
  case Success(res) => HandleResult(res)
}
```

## 第 6 步：引导与序列化注册 (Bootstrapping)

最后，在应用引导程序中初始化 Saga 引擎并注册您的序列化策略。

### 1. 策略注册
您必须在 `SerializationExtension` 中注册您的参与者，以便引擎知道该使用哪个转换器。

```scala
// 在您的引导类或 Module 中
val extension = SerializationExtension(system)
extension.registerStrategy(MyParticipantSerializerStrategy)
```

### 2. 分片初始化
使用 `SagaTransactionCoordinatorBootstrap` 启动协调器 Actor。

```scala
initSagaTransactionCoordinatorAggregate(
  sharding, 
  mySagaContext, 
  SagaTransactionCoordinator.entityTypeKey, 
  system
)
```

---

## 运维管理

- **挂起的事务 (Suspended)**：如果 `Compensate` 阶段失败，事务会进入 `SUSPENDED` 状态。使用展示页面的控制面板或 `ManualFixStep` 指令进行修复。
- **实时监控**：通过 Akka `eventStream` 订阅 `SagaTransactionCoordinator.Event` 以进行实时追踪。

## 最佳实践
1. **幂等性**：所有参与者操作 **必须** 是幂等的。
2. **Schema 演进**：永远不要更改 `.proto` 文件中的字段编号以保持兼容性。
3. **谨慎重试**：仅对瞬时技术故障使用 `RetryableFailure`，不要用于业务逻辑错误。
