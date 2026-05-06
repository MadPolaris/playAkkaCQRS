# Play-Akka-CQRS Project

## Project Identity

开源反应式 MES 内核，面向 Fab 晶圆厂。用事件溯源取代巨型事务，用 Saga 取代存储过程，用流批一体 DAG 执行引擎编排跨系统流程。

| Milestone | 状态 |
|-----------|------|
| M1 — Saga 分布式事务协调器 | ✅ 线上质量 |
| M2 — 流批一体多层级 DAG 执行引擎 | ✅ 线上质量 |
| M3 — 面向制造业控制论的 CIMs iPaaS | 🔮 探索中 |
| M4 — 即时反馈闭环 + 自适应 DAG 引擎 | 🔮 探索中 |

详见首页：`/` (EN) 和 `/zh` (中文)。

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
- **`.thenStop()` 陷阱**：Actor 完成后调用 `thenStop()` 时，用 `ref ! Command` + `TestProbe` 验证，不要用 `runCommand`（会 hang）
- **长 Saga 测试超时**：在 `ConfigFactory` 中显式调大：
  ```
akka.test.single-expect-default = 30s
akka.actor.testkit.typed.single-expect-default = 30s
  ```
- **测试失败先查逻辑**：持续 timeout 或非预期状态 → 先质疑测试前提，再改产品代码
- **EventSourcing 状态转换**：Error path 和 Saga Compensation 时，显式保留 `replyTo` 和 `reason` 到新 State

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
├── application/     Aggregate, ApplicationService, Projection, Saga
└── infrastructure/  Persistence adapter, Bootstrap, Guice module

app/views/           Play Twirl 模板（首页、Demo 页、文档页）
app/protobuf/        .proto 文件
conf/                application.conf / persistence.conf / cluster.conf
conf/sql/1.sql       MySQL read-side schema（docker-compose 自动初始化）
knowledge_base/      架构文档 & 方法论文档
test/                单元 + 集成测试
```