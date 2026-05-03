# Step-by-Step Saga Developer Guide

Developing a distributed transaction using the Saga framework requires a holistic approach, adhering to the **"Three-in-One" Rule**: synchronization between **Protobuf Schemas**, **Proto Converters**, and the **Scala Domain Model**.

---

## Step 1: Protobuf Schema (The Source of Truth)

All Saga participants must be serializable to persist the transaction state. Start by defining your participant's data structure in a `.proto` file.

```proto
// app/protobuf/saga_participant.proto
syntax = "proto3";
package net.imadz.infrastructure.proto;

message MyParticipantPO {
  string some_id = 1;
  int64 amount = 2;
}
```

## Step 2: Implement Proto Converters

The Saga engine uses these converters to transform your Scala participant objects into Protobuf messages (and vice-versa) for persistence and cluster communication.

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

## Step 3: Define Domain Protocol & Context

Define the commands for your Transactor Aggregate and a shared `Context` to inject dependencies into participants.

```scala
// Protocol
object MySagaProtocol {
  sealed trait Command extends CborSerializable
  case class StartSaga(id: Id, ...) extends Command
  // Callback for SagaCoordinator result
  case class HandleResult(res: TransactionResult) extends Command 
}

// Context
case class MySagaContext(repository: MyRepository, apiClient: MyClient)
```

## Step 4: Implement Saga Participants

Implement the `SagaParticipant` trait. This is where your business logic for **Prepare**, **Commit**, and **Compensate** lives.

```scala
case class MyParticipant(id: Id, amount: Long)(implicit ec: ExecutionContext) 
  extends SagaParticipant[MyError, MyResult, MySagaContext] {

  override def doPrepare(txId: String, ctx: MySagaContext, traceId: String) = {
    ctx.repository.reserve(id, amount).map(...)
  }
  
  override def doCommit(txId: String, ctx: MySagaContext, traceId: String) = ...
  override def doCompensate(txId: String, ctx: MySagaContext, traceId: String) = ...

  // Classify errors into Retryable vs. Non-Retryable
  override protected def customClassification = {
    case e: TimeoutException => RetryableFailure(e.getMessage)
  }
}
```

## Step 5: Orchestrate in the Transactor

Assemble the steps and initiate the transaction using the `SagaTransactionCoordinator`.

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

## Step 6: Bootstrapping & Serialization

Finally, initialize the Saga engine in your application bootstrap and register your serializers.

### 1. Registration
You must register your participants in the `SerializationExtension` so the engine knows which converter to use.

```scala
// In your Bootstrap/Module
val extension = SerializationExtension(system)
extension.registerStrategy(MyParticipantSerializerStrategy)
```

### 2. Sharding Initialization
Use the `SagaTransactionCoordinatorBootstrap` to start the coordinator actors.

```scala
initSagaTransactionCoordinatorAggregate(
  sharding, 
  mySagaContext, 
  SagaTransactionCoordinator.entityTypeKey, 
  system
)
```

---

## Operational Management

- **Suspended Transactions**: If a `Compensate` phase fails, the transaction enters `SUSPENDED`. Use the Showcase dashboard or `ManualFixStep` commands to resolve.
- **Monitoring**: Subscribe to `SagaTransactionCoordinator.Event` via the Akka `eventStream` for real-time tracking.

## Best Practices
1. **Idempotency**: All participant operations **MUST** be idempotent.
2. **Schema Evolution**: Never change field numbers in your `.proto` files to maintain compatibility.
3. **Conservative Retries**: Only use `RetryableFailure` for transient technical errors, not business logic errors.
