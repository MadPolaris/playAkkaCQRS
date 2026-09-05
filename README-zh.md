# Play Akka CQRS

[English](README.md) | 中文

面向半导体晶圆厂的响应式 MES 内核。用事件溯源取代巨型事务，用 Saga 取代存储过程，用流批一体 DAG 执行引擎编排跨系统流程。

| 里程碑 | 状态 |
|---|---|
| M1 — Saga 分布式事务协调器 | 线上质量 |
| M2 — 流批一体多层级 DAG 执行引擎 | 线上质量 |
| M3 — 面向制造业控制论的 CIMs iPaaS | 探索中 |
| M4 — 即时反馈闭环 + 自适应 DAG 引擎 | 探索中 |

项目首页：`/` (英文) 和 `/zh` (中文)。

## 快速上手

> 确保以下本地端口空闲：**2551**（Akka 远程通信）、**27017**（MongoDB 日志）、**3308**（MySQL 读库）。

### 1. 启动基础设施

```bash
docker-compose up -d   # MongoDB + MySQL
```

### 2. 启动应用

```bash
sbt run                # http://localhost:9000
```

> **Apple Silicon 芯片**：`sbt -Djna.nosys=true clean run`

### 3. 构建 Docker 镜像

```bash
sbt docker:publishLocal     # 构建镜像到本地 Docker daemon
docker run -p 9000:9000 minimal-cqrs:latest
```

推送到镜像仓库：

```bash
sbt -Ddocker.username=<用户名> -Ddocker.registry=<仓库地址> docker:publish
```

## 项目结构

```
├── common-core/       共享层：Akka 持久化、序列化、MongoDB/MySQL 适配器
├── saga-core/         M1：事件溯源 Saga 协调器、步骤执行器、回退恢复
├── app/
│   ├── net/imadz/
│   │   ├── domain/          纯领域层：Entity、ValueObject、DomainEvent、Invariant
│   │   ├── application/     用例层：Aggregate、ApplicationService、Projection、Saga
│   │   └── infrastructure/  适配层：Persistence、Bootstrap、DI（Guice）
│   ├── protobuf/            .proto 文件（ScalaPB）
│   └── views/               Play Twirl 模板（首页、Demo 页、文档页）
├── conf/                配置文件：application.conf、persistence.conf、cluster.conf
├── knowledge_base/      架构文档 & 方法论文档
└── test/                单元 + 集成测试
```

**依赖规则（铁律）**：内层绝不 import 外层包。

## 架构

洋葱架构 + DDD + CQRS/ES：

```
domain/          ← 纯领域层：纯函数为主，零外部依赖
application/     ← 用例层：编排领域逻辑，Aggregate、Saga、Projection
infrastructure/  ← 适配层：框架集成（Akka、Play、Guice、MongoDB、MySQL）
```

- **写端**：Akka EventSourcedBehavior → MongoDB 日志（Protobuf 序列化）
- **读端**：Akka Projection → MySQL（ScalikeJDBC），持续从事件更新
- **Saga**：TCC 模式（Try-Confirm-Cancel）→ 向后恢复 + 选择性补偿

完整架构参考：[`knowledge_base/architecture/onion-cqrs-reference.md`](knowledge_base/architecture/onion-cqrs-reference.md)

## M1 — Saga 分布式事务协调器

基于 Akka Persistence 的 TCC 模式实现。每个步骤运行在独立的 `StepExecutor` actor 中；`SagaTransactionCoordinator` 驱动状态机贯穿 Prepare → Commit → Compensate 阶段。补偿失败进入 `SUSPENDED` 状态，等待人工干预。

详见：[`knowledge_base/architecture/saga.md`](knowledge_base/architecture/saga.md)

## M2 — 多层级 DAG 执行引擎

6 种标准组件（Splitter、Merger、Classifier、Processor、Buffer、Router）组装成流水线。`ChainExecutionActor` 编排多层 DAG 执行。子批次流经定义的流水线；全局 `FailureItemRouter` 处理拒绝项。

包含 M2.5+ 运行时组件引擎，配合业务 DSL 实现 Fab M3 集成。

## M3 — 制造业控制论 CIMs iPaaS（探索中）

叠加于 M2.5+ 之上的决策层：POR Repository → 动态流程组装器 → Saga/LotContext 分布式事务 → ChainDSL 注入。MU 账户矩阵实现多维度可追溯。事实驱动闭环 — 零修改 M2.5+ 引擎。

## 技术栈

| 层级 | 技术 |
|---|---|
| 语言 | Scala 2.13.14 |
| Web 服务器 | Play Framework 2.8.18 |
| Actor 模型 | Akka 2.6.20（Cluster、Sharding、Persistence） |
| 事件日志 | MongoDB（Akka Persistence Mongo 插件） |
| 读模型 | MySQL 8.0（ScalikeJDBC + Akka Projection） |
| 序列化 | Protobuf（ScalaPB） |
| 依赖注入 | Guice |
| 构建 | sbt + sbt-native-packager（Docker） |

## 延伸阅读

- [Saga 引擎指南（v3 DSL）](docs/SAGA_GUIDE-zh.md) / [English](docs/SAGA_GUIDE.md) — TCC saga 引擎：DSL、韧性、运维、验收标准
- [ChainDsl 指南（M2.5+）](docs/CHAINDSL_GUIDE-zh.md) / [English](docs/CHAINDSL_GUIDE.md) — 批处理链组件引擎 + 声明式 DSL：流水线、三分类闭环、失败路由、成批调度、崩溃恢复
- [架构最佳实践](knowledge_base/methodology/architecture-best-practices-zh.md)
- [AI Agent 并行开发](knowledge_base/methodology/agent-parallel-dev-zh.md)
- [Harness 工程](knowledge_base/methodology/harness-engineering-zh.md)
