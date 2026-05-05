# Play-Akka-CQRS Project

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

## Methodology Docs

架构决策背后的"为什么"和团队协作方法论，详见 `knowledge_base/methodology/README.md`：

- [Harness Engineering](knowledge_base/methodology/harness-engineering.md) — 文档为什么这样组织？三层知识体系（规则→参考→指令）的理念
- [架构最佳实践](knowledge_base/methodology/architecture-best-practices.md) — 为什么用洋葱？为什么到处是接口？为什么文件拆这么小？FP 和 DSL 模式的动机
- [任务雷达](knowledge_base/methodology/task-radar.md) — 10+ 人如何通过 Miro 实时看板并行协作，无需加锁
- [Agent 并行开发](knowledge_base/methodology/agent-parallel-dev.md) — 未来愿景：任务雷达 + AI Agent 的人机混合开发模式

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

app/protobuf/        .proto 文件
conf/                application.conf / persistence.conf / cluster.conf
conf/sql/1.sql       MySQL read-side schema（docker-compose 自动初始化）
knowledge_base/      架构文档
test/                单元 + 集成测试
```
```

主要改动点：

- **Architecture 部分**加了依赖方向铁律，明确告诉 Claude 违反时要拒绝
- **FP 约定**独立成节，`Either`/`Option` 偏好、副作用隔离都显式声明
- **TDD 部分**把 Lessons Learned 里的测试陷阱整合进去，变成可操作的规则
- **精简了描述性文字**，把"是什么"压缩到最小，把"怎么做"放大
- **Protobuf 三件套**单独高亮，避免被淹没