# Play Akka CQRS

[English](README.md) | 中文

面向半导体晶圆厂的响应式 MES 内核。用事件溯源取代巨型事务，用 Saga 取代存储过程，用流批一体 DAG 执行引擎编排跨系统流程。

| 里程碑 | 状态 |
|---|---|
| M1 — Saga 分布式事务协调器（v3 引擎） | 线上质量 |
| Monarch —— 可断点续跑阶段队列引擎（monarch-core） | 线上质量——宿主：Fab M3.5 演示 + 充值链路 |
| M2 — 流批一体多层级 DAG 执行引擎 | 线上质量 |
| M2.5+ — ChainDsl 批处理链组件引擎 | 线上质量 |
| M3 — 面向制造业控制论的 CIMs iPaaS | 建设中：Lot Context Saga + M3.5 自愈演示 ← 线上质量 |
| M4 — 即时反馈闭环 + 自适应 DAG 引擎 | 探索中 |

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

## 在线演示

| 页面 | 演示内容 |
|---|---|
| `/` · `/zh` | 首页——CQRS/ES 银行示例（存款 / 取款 / 转账，转账走 TCC Saga） |
| `/saga` | Saga 演示场——触发场景、飞行中注入故障、人工修复并恢复挂起事务、WebSocket 实时事件流 |
| `/bank-batch` | **银行批量充值演示**——5 笔充值请求走六阶段 Monarch 链：逐笔三分类、可疑查证、失败自动重批、⚡宕机注入断点续跑、成功项真实入账 M1 账户 |
| `/fab-demo/m35` | **M3.5 FAB 自愈演示**——光刻 → CD-SEM 量测 → OCAP 返工，跑在事件溯源工单上；预排故障 *和* Actor 崩溃；亲眼看到 RECOVERING → 恢复阶段继续 → AllCompleted |
| `/architecture` `/projection` `/dag` `/m1` `/m2` `/m3` | 里程碑深入页面（英文在 `/en/m1`、`/en/m2`、`/en/m3`） |

## 项目结构

```
├── common-core/       共享层：Akka 持久化、序列化、MongoDB/MySQL 适配器
├── saga-core/         M1：saga_v3 引擎——SagaDefinition/SagaRunner/SagaRegistry DSL、
│                      分片协调器 + 每步骤×阶段 StepExecutor、日志优先人工修复、
│                      世代号守卫、向后恢复
├── monarch-core/      独立的可断点续跑阶段队列引擎（Monarch）——纯
│                      scala.concurrent、零 Akka；发布到 Maven Central
├── dag-engine-core/   M2.5+：批处理链组件——SubBatchPipeline、BankChain
│                      （六阶段 Monarch 队列）、分类 / 复核 / 路由 / 调度、
│                      ChainExecutionActor（事件溯源包装）
├── fab-simulation/    M3：设备适配器与模拟器协议（ActorEquipmentAdapter）
├── app/
│   ├── net/imadz/
│   │   ├── domain/          纯领域层：Entity、ValueObject、DomainEvent、Invariant
│   │   ├── application/     用例层：聚合根（CreditBalance、Lot、Wafer、WorkOrder）、
│   │   │                    投影、FabSaga 参与者、M3.5 链路执行、
│   │   │                    net.imadz.m25（ChainDsl、ChainTemplates、流水线阶段）
│   │   └── infrastructure/  适配层：Persistence、Bootstrap、DI（Guice）
│   ├── protobuf/            .proto 文件（ScalaPB）—— saga_v3 日志模式
│   └── views/               Play Twirl 模板（首页、Demo 页、文档页）
├── conf/                配置：application.conf、persistence.conf、cluster.conf、ocap-rules.conf
├── docs/                深入指南（SAGA、ChainDsl）+ 旧版 DDD 指南
├── knowledge_base/      架构文档 & 方法论文档
└── test/                单元 + 集成测试
```

**依赖规则（铁律）**：内层绝不 import 外层包。`monarch-core` 为纯 `scala.concurrent`（零 Akka），且是唯一发布到 Maven Central 的模块（打 `v*` 标签 → `sbt ci-release`）。

## 架构

洋葱架构 + DDD + CQRS/ES：

```
domain/          ← 纯领域层：纯函数为主，零外部依赖
application/     ← 用例层：编排领域逻辑，Aggregate、Saga、Projection
infrastructure/  ← 适配层：框架集成（Akka、Play、Guice、MongoDB、MySQL）
```

- **写端**：Akka EventSourcedBehavior → MongoDB 日志（按绑定分别使用 Protobuf / Java 序列化）
- **读端**：Akka Projection → MySQL（ScalikeJDBC），持续从事件更新
- **Saga**：TCC 模式（Try-Confirm-Cancel）→ 向后恢复 + 选择性补偿 + 日志优先人工修复

完整架构参考：[`knowledge_base/architecture/onion-cqrs-reference.md`](knowledge_base/architecture/onion-cqrs-reference.md)

## M1 — Saga 分布式事务协调器（v3 引擎）

基于 Akka Persistence 的 TCC 事务，由 **saga_v3 DSL** 驱动：链路声明为 `SagaDefinition`（步骤、步骤组、带 prepare/commit/compensate 的参与者），注册进 `SagaRegistry`，通过 `SagaRunner` 携带类型化参数编解码启动。一个分片的 `SagaTransactionCoordinator` 拥有事务状态机；每个步骤×阶段运行在独立的 `StepExecutor` 中，具备重试、超时、熔断和业务错误分类。

让恢复安全的设计不变量：

- **日志优先人工修复**——补偿失败进入挂起的步骤，用一条 `StepManuallyFixed` 事件完成修复；实体恢复时协调器从日志重新推导步骤结果，而不是去询问已死的执行器（不再出现幽灵 "Created" 占位状态）。
- **确定性重挂**——事务 ID 由 UUIDv3 派生，重启后的事务能幂等地重新挂载到自己的步骤上。
- **世代号守卫**——每个飞行中的操作携带尝试代号，被取代尝试的迟到响应直接丢弃。
- **参与者不进日志**——日志只记定义名/版本/参数；参与者从注册表重建，结构漂移会让事务挂起而不是瞎猜。

同一个引擎实例既驱动银行示例（`MoneyTransferSagaDefinition`），也驱动 Fab 的 lot 拆分/合并/晶圆转移 Saga——通过 `initSagaEngine[AppSagaContext]` 一次性接线。

详见：[Saga 引擎指南](docs/SAGA_GUIDE-zh.md) / [English](docs/SAGA_GUIDE.md) · [`knowledge_base/architecture/saga.md`](knowledge_base/architecture/saga.md)

## Monarch —— 可断点续跑阶段队列引擎（monarch-core）

本仓库第二个独立引擎，与 Saga 引擎同级：零 Akka、面向 Maven Central 发布的任意阶段队列执行器。三大机制以帝王斑蝶命名——完全变态（开放阶段队列）、滞育（游标断点续跑）、跨代迁徙（世代号，比任何单次运行都长寿）。

两条生产流水线都跑在它上面：Fab M3.5 自愈演示（17 个阶段——`Measure#9` 处崩溃、从同一游标恢复、一路走完 `SealComplete#16`）与 dag-engine-core 的充值链路（`BankChain`，6 个阶段，`PhaseDone` 携带快照 + `resumeFromIndex` 恢复）。

详见：[monarch-core/README.md](monarch-core/README.md) / [中文](monarch-core/README-zh.md) —— 引擎契约、五步建模法、两个宿主的完整案例。

## M2 — 多层级 DAG 执行引擎

6 种标准组件（Splitter、Merger、Classifier、Processor、Buffer、Router）组装成流水线。`ChainExecutionActor` 编排多层 DAG 执行。子批次流经定义的流水线；全局 `FailureItemRouter` 处理拒绝项。

## M2.5+ — ChainDsl 批处理链组件引擎

一套无 Akka 依赖的组件库（`dag-engine-core`）驱动一批业务物品走完外部系统交互周期——fileGen → upload → waitAck → poll → parse → classify——然后对每个 item 做三分类（成功 / 失败 / 可疑）：可疑项向权威数据源查证落定，失败项流经策略化路由器（`RetrySameArea` / `RouteToArea` / `Scrap` / `ManualIntervention`），窗口式成批尊重物理约束（FOUP 载体容量）。

`ChainDsl.define` 用约 20 行业务参数声明一条链；`ChainTemplates` 内置充值 / 申购 / 设备区预设。六个机械阶段由 **monarch-core**（`net.imadz.monarch`，独立、面向 Maven Central 的可断点续跑阶段队列引擎）驱动；两个宿主——Fab 移植版（`FabPipelineExecutionActor` + `FabPipelineProcessor`）与 dag-engine-core 的 `ChainExecutionActor`（经 `BankChain`）——都直接从引擎获得世代号崩溃恢复与回调守卫。

详见：[ChainDsl 指南](docs/CHAINDSL_GUIDE-zh.md) / [English](docs/CHAINDSL_GUIDE.md)

## M3 — 制造业控制论 CIMs iPaaS（建设中）

叠加于 M2.5+ 之上的决策层：POR Repository → 动态流程组装器 → Saga/LotContext 分布式事务 → ChainDSL 注入。MU 账户矩阵实现多维度可追溯。事实驱动闭环——零修改 M2.5+ 引擎。

**Lot Context Saga（线上质量）**：事件溯源的 `LotEntity` + `WaferEntity` 聚合根，配合 TCC Saga 参与者（`SourceLotParticipant`、`TargetLotParticipant`、`WaferTransferParticipant`）。Lot 拆分 / 合并 / 晶圆转移作为带 FOUP 容量不变量与向后恢复的分布式事务——与 M1 CreditBalance/MoneyTransfer 完全相同的 `InvariantRule` + `CommandHelper` + `SagaParticipant` 模式。

**M3.5 自愈演示（在线：`/fab-demo/m35`）**：5 片晶圆的光刻 → CD-SEM 量测 → OCAP 流程跑在事件溯源工单上（`FabPipelineExecutionActor`）。故障注入预排在流水线中途——包括完整的 Actor 崩溃：分片实体停止、以新世代重启、从日志阶段游标恢复（跳过已完成阶段、重跑被中断的那个）、把失败交给配置驱动的 OCAP 规则处置（返工 / 报废 / 挂起子流程，各自拥有 saga 管理的子 lot），最终到达 `AllCompleted`。页面通过 WebSocket 事件流渲染带镜头调度的 FAB 现场。

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
- [BankChain 全过程实操（中文）](docs/BANKCHAIN_WALKTHROUGH-zh.md) — 从业务问题定义、分解建模、方案设计到 DSL 映射与代码，再到 10 万客户规模验证的完整 walkthrough
- [Monarch 引擎（monarch-core）](monarch-core/README.md) — Fab 演示与充值链路共同寄宿的独立可续跑阶段队列引擎：开放阶段队列、游标断点续跑、世代号守卫
- [架构最佳实践](knowledge_base/methodology/architecture-best-practices-zh.md)
- [AI Agent 并行开发](knowledge_base/methodology/agent-parallel-dev-zh.md)
- [Harness 工程](knowledge_base/methodology/harness-engineering-zh.md)
