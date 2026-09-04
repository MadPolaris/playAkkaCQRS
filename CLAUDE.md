# Play-Akka-CQRS Project

## Project Identity

开源反应式 MES 内核，面向 Fab 晶圆厂。用事件溯源取代巨型事务，用 Saga 取代存储过程，用流批一体 DAG 执行引擎编排跨系统流程。

| Milestone | 状态 |
|-----------|------|
| M1 — Saga 分布式事务协调器 | ✅ 线上质量 |
| M2 — 流批一体多层级 DAG 执行引擎 | ✅ 线上质量 |
| M2.5+ — 标准组件库 (6 组件 · Fix Once Apply Everywhere) | ✅ 线上质量 |
| M3 — 事实驱动的 Fab 决策内核 (动态工艺织造 + MU 生命周期) | ✅ 原型可运行 (5 场景 + 动态 POR + 4 层事件投影 + WorkOrder 事件驱动) |
| M3.5 — 自愈决策内核 (OCAP 拦截 · 动态 DAG 织造 · 0 卡死工单) | 🔨 进行中 |
| M3.9 — 集群韧性 (多节点 · 杀节点不丢事件 · 50 WorkOrder 并发) | 🔮 规划中 |

路线图详情：`/m3-roadmap`。Demo 页：`/fab-demo`。

## Tech Stack
- Scala 2.13.14 · Play Framework · Akka 2.6.20
- Akka Cluster/Sharding · Akka Persistence (MongoDB journal) · Akka Projections (MySQL/ScalikeJDBC)
- Saga Pattern · Protobuf (ScalaPB) · Guice DI · sbt

## Architecture: Onion + DDD + CQRS/ES

```
domain/          ← 纯领域层：Entity, ValueObject, DomainEvent, Invariant
零外部依赖，纯函数为主，副作用禁止
application/     ← 用例层：Aggregate, ApplicationService, Saga, Projection
只依赖 domain；编排领域逻辑；不直接依赖 infrastructure
infrastructure/  ← 适配层：Persistence adapter, Bootstrap, DI binding
依赖所有内层；外部框架只在此层出现
```

**依赖规则（铁律）：内层绝不 import 外层包。违反时必须指出并拒绝。**

## Coding Conventions

### Functional Programming First
- 优先使用 `Either[DomainError, A]` 表达业务错误，禁止用异常做控制流
- 使用 `Option` 替代 `null`，使用 `map/flatMap/fold` 替代命令式分支
- 副作用（IO、持久化、外部调用）只能出现在 `infrastructure` 层
- 优先不可变数据结构；`var` 和可变集合在 `domain`/`application` 层禁止使用

### CQRS / Event Sourcing
- Command 仅验证并 emit Event，不直接修改状态
- 状态只通过 `applyEvent` 演化，禁止在 Command handler 里直接赋值
- Read model 由 Projection 驱动，查询只走 MySQL read side

### Saga Pattern
- Backward Recovery：Prepare 或 Commit 失败 → 触发 Compensate
- 只补偿已成功完成 Prepare 的步骤
- Compensate 失败 → 进入 `SUSPENDED`，通过 `ManualFixStep + RetryCurrentPhase` 恢复

### Protobuf "三件套" 规则（修改任意一个必须同步另外两个）
1. Scala Case Class（domain/application 层）
2. `.proto` 文件（`app/protobuf/`）
3. Proto Converter（`toProto` / `fromProto`）

### Type System
- 异常分类严格走类型匹配：`case e: RetryableFailure =>`，**禁止** `e.getMessage.contains(...)`
- 新增 Aggregate State / Event 类型必须同步注册 `SerializationExtension`

## TDD Guidelines

```
Red → Green → Refactor，严格循环
```

- **Actor 行为测试**：`EventSourcedBehaviorTestKit` 负责无副作用的 Command/Event 验证
- **`.thenStop()` 陷阱**：不要用 `runCommand`（会 hang），用 `ref ! Command` + `TestProbe` 验证
- **长 Saga 测试**：调大 timeout — `akka.test.single-expect-default = 30s`
- **EventSourcing 状态转换**：Error/Saga Compensation 时显式保留 `replyTo` 和 `reason` 到新 State

## Local Dev Setup

```bash
docker-compose up -d          # MongoDB (write) + MySQL (read)
sbt clean compile test        # 编译 + 测试（含 protobuf 自动生成）
sbt run                       # http://localhost:9000
```

## Directory Map

```
app/net/imadz/
├── domain/          Entity, ValueObject, Invariant
├── application/     Aggregate, Saga, Projection (aggregates/ Lot/Wafer/Process)
├── infrastructure/  Persistence, Bootstrap, Guice DI
└── fab/             M3 Demo: chain/ (DAG 引擎) · simulation/ (设备模拟器) · model/ (路由)
app/views/           Play Twirl 模板（首页、Demo、文档）
app/protobuf/        lot/wafer/process — 三件套
conf/                app/persistence/cluster.conf + routes + SQL schema
knowledge_base/      架构文档 · artifact 模板 · 方法论文档
test/                 单元 + 集成测试 (202 用例, 6 种 Pattern)
```

### Test Patterns (测试分层)

| Pattern | 测试对象 | 示例 |
|---------|---------|------|
| 1: Invariant Spec (AnyWordSpec) | 业务规则纯函数 | `LotInvariantSpec`, `WaferHoldReleaseInvariantSpec` |
| 2: Aggregate Spec (EventSourcedBehaviorTestKit) | Command→Event→State | `LotAggregateSpec`, `WaferAggregateSpec` |
| 3: Saga Transactor Spec | TCC 步骤生成 + 状态机 | `FabSagaTransactorSpec` |
| 4: Saga Integration Spec | 跨聚合 TCC 一致性 | `PhotoCellScenarioIntegrationSpec`, `SendAheadScenarioIntegrationSpec` |
| 5: Process Aggregate Spec | 状态机转换 | `FabProcessAggregateSpec` |

注意：`EventSourcedBehaviorTestKit` 为每个实例创建独立 journal。多个 TestKit 使用相同 persistenceId 不会共享事件。跨测试共享 deterministic UUID 会导致状态泄露（状态来源不明确），应使用 random UUID + `BeforeAndAfterEach`。

## Key References

- **DDD 架构违规（6 条永不再犯）**：Actor 不直接发 UI 事件、fire-and-forget 竞态、Future 回调超 Actor 生命周期、全局 mutable 状态传递、前后端事件契约错位、发布路径不一致 — 详情见 memory `ddd-architecture-violations`
- **Chain vs Saga 不混用**：Chain 管工序编排，Saga 管跨聚合 TCC 事务。OCAP 是路由决策点，不是新事务模式
- **一个事实只落一个地方**：效果已由 Event 持久化，不需要单独记录决策
- **知识库导航**：`knowledge_base/architecture/` (分层定义) · `knowledge_base/artifacts/` (30 种代码模板) · `knowledge_base/methodology/` (架构哲学)
- **维护**：`/harness` 整理 Harness Engineering 环境