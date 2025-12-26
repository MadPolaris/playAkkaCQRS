这里是为您翻译的 `SAGA_ENGINE_README.md` 英文版。

您可以直接保存为 `SAGA_ENGINE_README-en.md`。

---

# iMadz Saga Engine (v2.0) Architecture White Paper & Development Guide

## 1. Engine Overview (Executive Summary)

The iMadz Saga Engine is a distributed transaction orchestration engine built upon **Akka Cluster Sharding** and **Akka Persistence (Event Sourcing)**. It is designed specifically to solve the problem of eventual data consistency in microservices architectures by adopting the **TCC (Try-Confirm-Cancel)** pattern.

The **core transformation in v2.0** lies in the introduction of the **"Runtime Context Injection"** pattern. This thoroughly decouples "business data" from "execution capability," turning Saga Participants into pure Data Transfer Objects (DTOs), which drastically simplifies serialization and testing complexity.

---

## 2. Key Features

### 2.1 🛡️ Type-Safe Dependency Injection

* **Feature Description**: Abandons traditional global static lookup or complex constructor injection.
* **Mechanism**: The Saga Participant (`Participant`) holds only the business ID (e.g., `userId`, `amount`). "Heavy resources" such as database connections and RPC clients are encapsulated within a generic `Context`.
* **Advantage**: Serialization is extremely lightweight (storing only IDs), and it ensures at compile-time that business logic acquires the correct Repository.

### 2.2 🧩 Separation of State & Behavior

* **Feature Description**: Strictly adheres to functional programming principles.
* **Mechanism**:
* **State (Data)**: Carried by the `SagaParticipant` class, responsible for persistence to MongoDB.
* **Behavior (Logic)**: Defined by the `Participant.doPrepare(context)` method, executed at runtime in combination with the Context.
* **Capability**: Carried by the `Context`, injected via Guice/Play during the Bootstrap phase.



### 2.3 ⚡ High Availability & Auto-Sharding

* **Feature Description**: Based on Akka Cluster Sharding.
* **Mechanism**: Each Saga transaction (Coordinator) is an independent Actor. The system automatically distributes the load across all nodes in the cluster based on the `TransactionId`.
* **Advantage**: Natively supports Scale Out and possesses Location Transparency.

### 2.4 💾 Event Sourcing & Self-Healing

* **Feature Description**: Every state change in a transaction (Started, PhaseSucceeded, StepFailed) is written to the log as an event.
* **Mechanism**: When a service crashes and restarts, Sharding automatically recreates the Actor. The Actor restores its state by replaying the event log and automatically resumes unfinished steps.

### 2.5 🔍 Full-Link Tracing & Observability

* **Feature Description**: Built-in detailed execution link tracing.
* **Mechanism**: `TransactionResult` contains a complete list of `TracingStep`s, recording the execution status, retry count, duration, and specific failure reasons for each step.

### 2.6 Bulkheading Design

* **Feature Description**: Supports multi-tenant/multi-business line isolation.
* **Mechanism**: By manually specifying the `EntityTypeKey` (e.g., `"Saga-MoneyTransfer"`, `"Saga-OrderProcess"`), different business processes run in logically isolated sharding regions without interfering with each other.

---

## 3. Core Component Manifest (The Full Manifest)

To develop a complete Saga transaction (e.g., `OrderProcess`), you **must** implement all of the following components. None can be missing.

| Layer | Component Name | Role | Key Code Location |
| --- | --- | --- | --- |
| **0. Protocol** | `order.proto` | **[New]** Defines the data contract. Akka Persistence does not store Java objects directly, only Proto. | `app/protobuf/` |
| **1. Context** | `OrderContext` | **Toolbox**. Encapsulates Repositories, injected at runtime. | `app/.../saga/context/` |
| **2. Participant** | `InventoryParticipant` | **Execution Logic**. Pure data object, implements Prepare/Commit. | `app/.../services/transactor/` |
| **3. Converter** | `InventoryParticipantConv` | **[New]** Conversion logic for `Participant` <-> `Proto`. | `app/.../persistence/converters/` |
| **4. Strategy** | `InventoryStrategy` | **[New]** Tells the engine how to serialize this Participant. | `strategies/TransactionSerializationStrategies.scala` |
| **5. Adapter** | `OrderSagaEventAdapter` | **[New]** Responsible for persistence conversion of the Saga's own state events. | `app/.../persistence/` |
| **6. Orchestrator** | `OrderSagaTransactor` | **Flowchart**. Defines the list of steps. | `app/.../services/transactor/` |
| **7. Config** | `serialization.conf` | **[New]** Registers binding relationships. | `conf/serialization.conf` |
| **8. Bootstrap** | `ApplicationBootstrap` | **Assembly**. Injects Context, starts Sharding. | `app/.../bootstrap/` |

---

## 4. Step-by-Step Implementation

We will use **"E-commerce Order Placement (OrderProcess)"** as an example to demonstrate the full process.

### Step 1: Define Protobuf (The Contract)

Create `order_saga.proto` under `app/protobuf/`. This is the cornerstone of persistence.

```protobuf
syntax = "proto3";
package net.imadz.infrastructure.proto;

// 1. Define the Participant's data structure (for storage in MongoDB)
message InventoryParticipantPO {
  string product_id = 1;
  int32 count = 2;
}

// 2. If the Saga has custom events, define them here as well
// (Usually Saga reuses the generic TransactionStartedPO unless you have special requirements)

```

**Compile**: Run `sbt compile` to generate Scala classes.

---

### Step 2: Define Context (The Context)

Define the toolbox in `app/net/imadz/application/saga/context/`.

```scala
case class OrderContext(
  inventoryRepo: InventoryRepository,
  orderRepo: OrderRepository,
  // You can even put Sharding here to call other Actors
  sharding: ClusterSharding 
)

```

---

### Step 3: Implement Participant (The Participant)

Implement the logic in `app/net/imadz/application/services/transactor/`.

```scala
// Generic C is specified as OrderContext
case class InventoryParticipant(productId: String, count: Int) 
  extends SagaParticipant[iMadzError, String, OrderContext] {

  // Business logic: only use ctx, do not hold Repo
  override def doPrepare(txId: String, ctx: OrderContext): Future[...] = {
    ctx.inventoryRepo.reserve(productId, count)
  }
  // ... doCommit, doCompensate
}

```

---

### Step 4: Write Converter and Strategy (The Serialization Layer)

**This is the step most easily missed!** To allow the Participant to be stored in the database, you must tell Akka how to convert it to Proto.

**1. Write Converter (Scala <-> Proto)**
In `app/net/imadz/infrastructure/persistence/converters/`:

```scala
object InventoryParticipantConv extends ProtoConverter[InventoryParticipant, InventoryParticipantPO] {
  override def toProto(d: InventoryParticipant): InventoryParticipantPO = 
    InventoryParticipantPO(d.productId, d.count)

  override def fromProto(p: InventoryParticipantPO): InventoryParticipant = 
    InventoryParticipant(p.productId, p.count)
}

```

**2. Write Strategy (Integrate into Engine)**
In `app/net/imadz/infrastructure/persistence/strategies/TransactionSerializationStrategies.scala`:

```scala
object TransactionSerializationStrategies {
  // ... other strategies

  // Add InventoryStrategy
  case object InventoryStrategy extends SagaParticipantSerializerStrategy {
    override def manifest: String = "InventoryParticipant" // Mark for storage
    override def participantClass: Class[_] = classOf[InventoryParticipant]

    override def toBinary(p: SagaParticipant[_, _, _]): Array[Byte] = {
      val part = p.asInstanceOf[InventoryParticipant]
      InventoryParticipantConv.toProto(part).toByteArray
    }

    override def fromBinary(bytes: Array[Byte]): SagaParticipant[_, _, _] = {
      val proto = InventoryParticipantPO.parseFrom(bytes)
      InventoryParticipantConv.fromProto(proto)
    }
  }
}

```

---

### Step 5: Register Serialization Strategy (The Registration)

Register it in `ApplicationBootstrap.scala`.

```scala
// Inside the ApplicationBootstrap initialization block:
serializationExtension.registerStrategy(TransactionSerializationStrategies.InventoryStrategy)

```

---

### Step 6: Persistence Adapter (The Persistence Layer - Optional)

If your Saga reuses the generic `SagaTransactionCoordinator`, you usually **do not need** to write a new `EventAdapter`, because the framework's built-in `SagaTransactionCoordinatorEventAdapter` already handles the generic `TransactionStarted` event and will use the `Strategy` above to serialize the `Participant`.

**However**, if you have defined special states or events for your Saga, you need to register the adapter in `persistence.conf`.

*(In most cases, you can skip this step and reuse the framework capabilities directly)*

---

### Step 7: Wiring & Bootstrap

**1. Choreograph Steps**

```scala
object OrderTransactor {
  // Define unique Sharding Key
  val typeKey = EntityTypeKey[Command]("Saga-OrderProcess")

  def createSteps(pid: String, count: Int): List[SagaTransactionStep[..., OrderContext]] = {
    val part = InventoryParticipant(pid, count)
    List(
      SagaTransactionStep("step-1", PreparePhase, part),
      // ...
    )
  }
}

```

**2. Bootstrap Assembly (ApplicationBootstrap.scala)**

```scala
class ApplicationBootstrap @Inject()(..., inventoryRepo: InventoryRepository) {
  
  // 1. Prepare Context
  val orderCtx = OrderContext(inventoryRepo, ...)

  // 2. Start Saga Cluster (Inject Context)
  initSagaTransactionCoordinatorAggregate[OrderContext](
    sagaName = "Saga-OrderProcess", // Corresponds to the TypeKey name above
    sharding = sharding,
    context = orderCtx,             // <--- Core Injection
    system = system
  )
}

```

---

## 5. Configuration Checklist

Finally, check `conf/serialization.conf`. If you have defined new Top-Level Events, they must be bound here. For `SagaParticipant`, usually no extra configuration is needed because they are wrapped inside `SagaTransactionStep`, and `SagaTransactionStep` is already configured.

**Ensure the following configurations exist** (should be configured in the framework layer):

```hocon
akka.actor {
  serializers {
    saga-serializer = "net.imadz.infra.saga.serialization.SagaSerializer"
  }
  serialization-bindings {
    # Ensure Step can be serialized
    "net.imadz.infra.saga.SagaTransactionStep" = saga-step-serializer
    # Ensure Participant wrapper class can be serialized
    "net.imadz.infra.saga.SagaParticipant" = saga-serializer 
  }
}

```

---

## 6. Development Self-Check Mantra (Definition of Done)

Before committing code, developers should recite this mantra:

1. **Is Proto defined?** (Does `.proto` contain the new Participant's fields?)
2. **Is Converter written?** (Can the Scala object be converted to Proto?)
3. **Is Strategy registered?** (Is `registerStrategy` added in `ApplicationBootstrap`?)
4. **Is Context passed?** (Is the correct Context instance passed in `initSaga` in `Bootstrap`?)
5. **Is Key unique?** (Does the `EntityTypeKey` name conflict with other Sagas?)

If all the above are **Yes**, congratulations, your Saga engine code will **pass on the first try**!