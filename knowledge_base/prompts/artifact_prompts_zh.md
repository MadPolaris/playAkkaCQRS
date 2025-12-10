# 物理交付物提示词矩阵 (Artifact Prompt Matrix)

本文档定义了系统中各类 **物理交付物 (Artifact)** 的生成标准。
AI Agent 在生成代码时，必须严格遵守对应 Artifact 章节的约束。

## 0. 全局约束 (Global Constraints)
1.  **语言**: Scala 2.13
2.  **核心栈**: Akka Typed (Actor), Akka Persistence (Event Sourcing), Akka Cluster Sharding.
3.  **风格**:
    - 纯函数式编程 (FP) 优先。
    - 默认不可变 (Immutable by default)。
    - **严禁**使用 `var`, `null`, `throw Exception` (使用 `Option`/`Either`)。
4.  **类型安全**: 杜绝 `Any`, `Object`。必须使用强类型 (如 `Id`, `Money`)。

---

## 1. 领域层 (Domain Layer)
**原则**: 纯 Scala 环境，零框架依赖 (Zero Framework Dependency)。

### 1.1 [Artifact] Value Object (值对象)
* **文件**: `domain/values/*.scala`
* **结构**: `final case class`。
* **职责**: 封装数据 + 纯逻辑 (Rich Domain Model)。
* **约束**:
    -   必须包含业务逻辑 (如 `+`, `-`, `compare`)，不仅仅是数据容器。
    -   校验失败返回 `Option` 或 `Either`，严禁抛异常。

### 1.2 [Artifact] Domain Entity (实体定义)
* **文件**: `domain/entities/${Aggregate}Entity.scala`
* **内容**:
    -   `case class ${Aggregate}State`: 聚合状态定义。
    -   `sealed trait ${Aggregate}Event`: 领域事件父接口。
    -   `object ${Aggregate}Entity`: 包含 `empty(id)` 工厂方法。
* **约束**:
    -   状态必须包含所有业务数据。
    -   事件必须完整覆盖所有业务场景。

### 1.3 [Artifact] Domain Behavior (事件处理器)
* **文件**: `domain/entities/behaviors/${Aggregate}EventHandler.scala`
* **职责**: 纯函数 `(State, Event) => State`。
* **约束**:
    -   **纯逻辑**: 仅负责状态变更 (Mutation)，不负责副作用。
    -   对应 Metadata 中的 `EventStateMutation`。

### 1.4 [Artifact] Domain Policy (领域策略)
* **文件**: `domain/policy/${Policy}.scala`
* **职责**: 业务规则校验器。
* **签名**: `apply(state, param): Either[Error, List[Event]]`。
* **约束**: 必须实现 `net.imadz.common.CommonTypes.DomainPolicy`。

---

## 2. 应用层 (Application Layer)
**原则**: 编排 (Orchestration) 与 门面 (Facade)。负责连接 Web 与 Domain。

### 2.1 [Artifact] Aggregate Protocol (聚合壳)
* **文件**: `application/aggregates/${Aggregate}Aggregate.scala`
* **内容**:
    -   `sealed trait Command` (CborSerializable)
    -   `case class Reply` (必须遵循 `XxxConfirmation(error, data)` 格式)
    -   `EntityTypeKey` & `Tags` 定义
* **约束**:
    -   **无逻辑**: 这里只定义协议，逻辑委托给 Behaviors。

### 2.2 [Artifact] Aggregate Behaviors (聚合核)
* **文件**: `application/aggregates/behaviors/${Aggregate}Behaviors.scala`
* **职责**: Command Handler (`(State, Command) => Effect`).
* **模式**: **行为组合 (Composition)**。
    -   使用 `PartialFunction` 组合逻辑 (e.g. `directOps.orElse(reserveOps)`).
    -   **DSL**: 必须使用 `runReplyingPolicy(Policy)...` DSL 处理业务规则。
    -   **严禁**: 裸写 `if/else` 进行业务判断。

### 2.3 [Artifact] Aggregate Factory (工厂)
* **文件**: `application/aggregates/factories/${Aggregate}Factory.scala`
* **职责**: 封装 Cluster Sharding 的 `entityRefFor` 调用。
* **方法**: `get(id): EntityRef`, `create(id, initial): Future`.

### 2.4 [Artifact] Saga Transactor (事务门面)
* **文件**: `application/services/transactor/${Saga}Transactor.scala`
* **职责**: Saga 的 Actor 定义与协议。
* **约束**:
    -   这是一个 **Facade**，不包含状态机逻辑。
    -   它只负责向 `SagaTransactionCoordinator` 发送 `StartTransaction` 指令。

### 2.5 [Artifact] Saga Participant (参与者)
* **文件**: `application/services/transactor/${Participant}.scala`
* **职责**: 实现 `SagaParticipant[E, R]` 接口。
* **方法**: `doPrepare`, `doCommit`, `doCompensate`。
* **关键**: 必须实现 `customClassification` 以区分 Retryable/NonRetryable 错误。

---

## 3. 基础设施层 (Infrastructure Layer)
**原则**: 技术实现与胶水代码 (Glue Code)。

### 3.1 [Artifact] Repository Implementation
* **文件**: `infrastructure/repositories/aggregate/` 或 `projection/`
* **技术**:
    -   **Aggregate**: 使用 `ClusterSharding.entityRefFor`。
    -   **Projection**: 使用 `ScalikeJDBC` (MySQL dialect)。
* **约束**: 必须实现应用层定义的 `Repository Trait`。

### 3.2 [Artifact] Saga Step Serializer (多路复用器)
* **文件**: `infrastructure/persistence/${Saga}StepSerializer.scala`
* **模式**: **Payload Multiplexing**。
    -   将具体的业务对象 (e.g. `FromAccountPO`) 序列化为字节。
    -   包装进通用的 `SagaParticipantPO` (Generic Envelope)。
* **目的**: 隔离通用 Saga 引擎与具体业务 Proto。

### 3.3 [Artifact] Bootstrap (引导程序)
* **文件**: `infrastructure/bootstrap/${Name}Bootstrap.scala`
* **职责**: 初始化 Sharding, Projection, Serializers。
* **约束**:
    -   Persistence: 配置 `EventAdapter` 和 `SnapshotAdapter`。
    -   Resilience: 配置 `SupervisorStrategy.restartWithBackoff`。