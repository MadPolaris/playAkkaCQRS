### Component: Aggregate Root

**Concept**:
一致性边界的核心。在本项目中，聚合根被物理拆解为“洋葱架构”的多个层次。

#### A. Domain Layer (Pure Core)
1.  **[Artifact] Domain Entity** (`DomainEntityArtifact`)
    * **File**: `domain/entities/${Name}Entity.scala`
    * **职责**: 定义 `State` (case class), `Event` (sealed trait), `Entity` Object (含 `empty` 工厂)。
2.  **[Artifact] Domain Behavior** (`DomainBehaviorArtifact`)
    * **File**: `domain/entities/behaviors/${Name}EventHandler.scala`
    * **职责**: 纯函数 `(State, Event) => State`。对应 Metadata 中的 `EventStateMutation`。

#### B. Application Layer (Orchestration)
3.  **[Artifact] Aggregate Protocol** (`AggregateProtocolArtifact`)
    * **File**: `application/aggregates/${Name}Aggregate.scala`
    * **职责**: 定义 `Command` (sealed trait), `Reply` (case class), `EntityTypeKey`, `Tags`。**无业务逻辑**。
4.  **[Artifact] Aggregate Behaviors** (`AggregateCommandBehaviorArtifact`)
    * **File**: `application/aggregates/behaviors/${Name}Behaviors.scala`
    * **职责**: 实现 Command Handler。
    * **核心模式**: 使用 `runReplyingPolicy(Policy)...` DSL。严禁裸写 `if/else`。
5.  **[Artifact] Aggregate Factory** (`AggregateFactoryArtifact`)
    * **File**: `application/aggregates/factories/${Name}Factory.scala`
    * **职责**: 封装 Cluster Sharding 的引用获取 (`entityRefFor`)。
6.  **[Artifact] Repository Interface** (`AggregateRepositoryTraitArtifact`)
    * **File**: `application/aggregates/repository/${Name}Repository.scala`
    * **职责**: 定义数据访问端口 (Port)。

#### C. Infrastructure Layer (Glue)
7.  **[Artifact] Bootstrap** (`AggregateBootstrapArtifact`)
    * **File**: `infrastructure/bootstrap/${Name}Bootstrap.scala`
    * **职责**: 初始化 Sharding, 注册 Adapters。
8.  **[Artifact] Adapters** (`PersistenceAdapterArtifact`, `SnapshotAdapterArtifact`)
    * **File**: `infrastructure/persistence/*Adapter.scala`
    * **职责**: Domain Object <-> Protobuf 转换。
9.  **[Artifact] Repository Impl** (`RepositoryImplArtifact`)
    * **File**: `infrastructure/repositories/aggregate/${Name}RepositoryImpl.scala`
    * **职责**: 实现 Repository 接口。