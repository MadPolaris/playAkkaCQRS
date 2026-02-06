施主，这是一份为您整理的完整文档。它不仅包含了之前的开发指南，还总结了您这套 Saga 引擎的**核心架构特性**。

您可以直接将以下内容保存为 `SAGA_ENGINE_README.md`，作为团队的架构白皮书。

---

# iMadz Saga Engine (v2.0) 架构白皮书 & 开发指南

## 1. 引擎概览 (Executive Summary)

iMadz Saga Engine 是一套基于 **Akka Cluster Sharding** 和 **Akka Persistence (Event Sourcing)** 构建的分布式事务编排引擎。它专为解决微服务架构下的数据最终一致性问题而设计，采用 **TCC (Try-Confirm-Cancel)** 模式。

**v2.0 版本的核心变革**在于引入了**“运行时上下文注入 (Runtime Context Injection)”**模式，彻底解耦了“业务数据”与“执行能力”，使得 Saga 参与者（Participant）变为纯粹的数据传输对象（DTO），极大地简化了序列化与测试复杂度。

---

## 2. 核心特性 (Key Features)

### 2.1 🛡️ 类型安全的依赖注入 (Type-Safe Dependency Injection)

* **特性描述**：摒弃了传统的全局静态查找（Static Lookup）或复杂的构造函数注入。
* **机制**：Saga 参与者（`Participant`）只持有业务 ID（如 `userId`, `amount`）。数据库连接、RPC 客户端等“重资源”被封装在泛型上下文 `Context` 中。
* **优势**：序列化极其轻量（只存 ID），且在编译期就能保证业务逻辑获得了正确的 Repository。

### 2.2 🧩 状态与行为分离 (Separation of State & Behavior)

* **特性描述**：严格遵循函数式编程思想。
* **机制**：
* **State (数据)**：由 `SagaParticipant` 类承载，负责持久化到 MongoDB。
* **Behavior (逻辑)**：由 `Participant.doPrepare(context)` 方法定义，运行时结合 Context 执行。
* **Capability (能力)**：由 `Context` 承载，在 Bootstrap 阶段由 Guice/Play 注入。



### 2.3 ⚡ 高可用与自动分片 (HA & Auto-Sharding)

* **特性描述**：基于 Akka Cluster Sharding。
* **机制**：每一个 Saga 事务（Coordinator）都是一个独立的 Actor。系统根据 `TransactionId` 自动将负载分布到集群的所有节点上。
* **优势**：天然支持水平扩展（Scale Out），且具备位置透明性（Location Transparency）。

### 2.4 💾 事件溯源与故障自愈 (Event Sourcing & Self-Healing)

* **特性描述**：事务的每一步状态变更（Started, PhaseSucceeded, StepFailed）都以事件形式写入日志。
* **机制**：当服务崩溃重启时，Sharding 会自动重新创建 Actor，Actor 通过重放（Replay）事件日志恢复状态，并自动继续未完成的步骤。

### 2.5 🔍 全链路追踪与可观测性 (Tracing & Observability)

* **特性描述**：内置详细的执行链路追踪。
* **机制**：`TransactionResult` 包含完整的 `TracingStep` 列表，记录了每一个步骤的执行状态、重试次数、耗时以及具体的失败原因。

### 2.6 隔离性设计 (Bulkheading)

* **特性描述**：支持多租户/多业务线隔离。
* **机制**：通过手动指定 `EntityTypeKey`（如 `"Saga-MoneyTransfer"`, `"Saga-OrderProcess"`），不同的业务流程运行在逻辑隔离的分片区域，互不干扰。

---


## 3. 核心组件清单 (The Full Manifest)

开发一个完整的 Saga 事务（如 `OrderProcess`），您**必须**实现以下所有组件。缺一不可。

| 分层 | 组件名 | 作用 | 关键代码位置 |
| --- | --- | --- | --- |
| **0. 协议层** | `order.proto` | **[新增]** 定义数据契约。Akka Persistence 不直接存 Java 对象，只存 Proto。 | `app/protobuf/` |
| **1. 上下文** | `OrderContext` | **工具箱**。封装 Repository，运行时注入。 | `app/.../saga/context/` |
| **2. 参与者** | `InventoryParticipant` | **执行逻辑**。纯数据对象，实现 Prepare/Commit。 | `app/.../services/transactor/` |
| **3. 转换器** | `InventoryParticipantConv` | **[新增]** `Participant` <-> `Proto` 的转换逻辑。 | `app/.../persistence/converters/` |
| **4. 策略** | `InventoryStrategy` | **[新增]** 告诉引擎如何序列化这个 Participant。 | `strategies/TransactionSerializationStrategies.scala` |
| **5. 适配器** | `OrderSagaEventAdapter` | **[新增]** 负责 Saga 自身状态事件的持久化转换。 | `app/.../persistence/` |
| **6. 编排者** | `OrderSagaTransactor` | **流程图**。定义步骤列表。 | `app/.../services/transactor/` |
| **7. 配置** | `serialization.conf` | **[新增]** 注册绑定关系。 | `conf/serialization.conf` |
| **8. 启动** | `ApplicationBootstrap` | **组装**。注入 Context，启动分片。 | `app/.../bootstrap/` |

---

## 4. 实施步骤 (Step-by-Step Implementation)

我们以 **“电商下单（OrderProcess）”** 为例，演示全流程。

### 第一步：定义 Protobuf (The Contract)

在 `app/protobuf/` 下创建 `order_saga.proto`。这是持久化的基石。

```protobuf
syntax = "proto3";
package net.imadz.infrastructure.proto;

// 1. 定义参与者的数据结构 (用于存 MongoDB)
message InventoryParticipantPO {
  string product_id = 1;
  int32 count = 2;
}

// 2. 如果 Saga 有自定义事件，也在这里定义
// (通常 Saga 复用通用的 TransactionStartedPO，除非你有特殊需求)

```

**编译**：运行 `sbt compile` 生成 Scala 类。

---

### 第二步：定义上下文 (The Context)

在 `app/net/imadz/application/saga/context/` 定义工具箱。

```scala
case class OrderContext(
  inventoryRepo: InventoryRepository,
  orderRepo: OrderRepository,
  // 甚至可以放 Sharding 用来调其他 Actor
  sharding: ClusterSharding 
)

```

---

### 第三步：实现参与者 (The Participant)

在 `app/net/imadz/application/services/transactor/` 实现逻辑。

```scala
// 泛型 C 指定为 OrderContext
case class InventoryParticipant(productId: String, count: Int) 
  extends SagaParticipant[iMadzError, String, OrderContext] {

  // 业务逻辑：只使用 ctx，不持有 Repo
  override def doPrepare(txId: String, ctx: OrderContext): Future[...] = {
    ctx.inventoryRepo.reserve(productId, count)
  }
  // ... doCommit, doCompensate
}

```

---

### 第四步：编写转换器与策略 (The Serialization Layer)

**这是最容易漏掉的一步！** 为了让 Participant 能存进数据库，必须告诉 Akka 如何转 Proto。

**1. 编写 Converter (Scala <-> Proto)**
在 `app/net/imadz/infrastructure/persistence/converters/`：

```scala
object InventoryParticipantConv extends ProtoConverter[InventoryParticipant, InventoryParticipantPO] {
  override def toProto(d: InventoryParticipant): InventoryParticipantPO = 
    InventoryParticipantPO(d.productId, d.count)

  override def fromProto(p: InventoryParticipantPO): InventoryParticipant = 
    InventoryParticipant(p.productId, p.count)
}

```

**2. 编写 Strategy (集成到引擎)**
在 `app/net/imadz/infrastructure/persistence/strategies/TransactionSerializationStrategies.scala`：

```scala
object TransactionSerializationStrategies {
  // ... 其他策略

  // 新增 InventoryStrategy
  case object InventoryStrategy extends SagaParticipantSerializerStrategy {
    override def manifest: String = "InventoryParticipant" // 存库时的标记
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

### 第五步：注册序列化策略 (The Registration)

在 `ApplicationBootstrap.scala` 中注册它。

```scala
// 在 ApplicationBootstrap 初始化块中：
serializationExtension.registerStrategy(TransactionSerializationStrategies.InventoryStrategy)

```

---

### 第六步：持久化适配 (The Persistence Layer - Optional)

如果您的 Saga 复用了通用的 `SagaTransactionCoordinator`，通常**不需要**写新的 `EventAdapter`，因为框架自带的 `SagaTransactionCoordinatorEventAdapter` 已经处理了通用的 `TransactionStarted` 事件，并会使用上面的 `Strategy` 来序列化 `Participant`。

**但是**，如果您为 Saga 自定义了特殊状态或事件，您需要在 `persistence.conf` 里注册适配器。

*(大多数情况下，这一步您可以跳过，直接复用框架能力)*

---

### 第七步：编排与启动 (Wiring & Bootstrap)

**1. 编排步骤**

```scala
object OrderTransactor {
  // 定义唯一的 Sharding Key
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

**2. 启动装配 (ApplicationBootstrap.scala)**

```scala
class ApplicationBootstrap @Inject()(..., inventoryRepo: InventoryRepository) {
  
  // 1. 准备 Context
  val orderCtx = OrderContext(inventoryRepo, ...)

  // 2. 启动 Saga 集群 (注入 Context)
  initSagaTransactionCoordinatorAggregate[OrderContext](
    sagaName = "Saga-OrderProcess", // 对应上面的 TypeKey 名字
    sharding = sharding,
    context = orderCtx,             // <--- 核心注入
    system = system
  )
}

```

---

## 5. 配置文件检查清单 (Checklist)

最后，检查 `conf/serialization.conf`。如果您定义了新的 Top-Level Event，必须在这里绑定。对于 `SagaParticipant`，通常不需要额外配置，因为它们被包裹在 `SagaTransactionStep` 中，而 `SagaTransactionStep` 已经配置过了。

**确保以下配置存在** (框架层应已配好):

```hocon
akka.actor {
  serializers {
    saga-serializer = "net.imadz.infra.saga.serialization.SagaSerializer"
  }
  serialization-bindings {
    # 确保 Step 能被序列化
    "net.imadz.infra.saga.SagaTransactionStep" = saga-step-serializer
    # 确保 Participant 包装类能被序列化
    "net.imadz.infra.saga.SagaParticipant" = saga-serializer 
  }
}

```

---

## 6. 开发自检口诀 (Definition of Done)

开发者提交代码前，请默念此口诀：

1. **Proto 定了吗？** (`.proto` 是否包含新 Participant 的字段)
2. **Converter 写了吗？** (Scala 对象能转成 Proto 吗)
3. **Strategy 注册了吗？** (`ApplicationBootstrap` 里 `registerStrategy` 加上了吗)
4. **Context 传了吗？** (`Bootstrap` 里 `initSaga` 传的是正确的 Context 实例吗)
5. **Key 唯一吗？** (`EntityTypeKey` 名字是否和其他 Saga 冲突)

如果以上全是 **Yes**，恭喜你，你的 Saga 引擎代码将**一次通过**！