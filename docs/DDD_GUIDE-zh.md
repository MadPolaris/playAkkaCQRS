# DDD 落地指南 —— 每个概念在本仓库中的实现

[English](DDD_GUIDE.md) | 中文

本指南把每一个 **DDD（领域驱动设计）** 战术模式映射到仓库中实现它的精确文件，包括文件数量、代码摘录与层间装配关系。首次请从头阅读，之后可用文末的汇总矩阵作速查表。

其他文档：[README](../README-zh.md) · [Saga 指南](SAGA_GUIDE-zh.md)

---

## 目录

1. [依赖规则（洋葱架构）](#1-依赖规则洋葱架构)
2. [概念逐个落地](#2-概念逐个落地)
   - [值对象](#21-值对象)
   - [领域事件](#22-领域事件)
   - [聚合状态](#23-聚合状态)
   - [事件处理器（状态演进）](#24-事件处理器状态演进)
   - [不变式规则](#25-不变式规则)
   - [领域服务](#26-领域服务)
   - [命令与协议](#27-命令与协议)
   - [命令处理器与命令辅助器](#28-命令处理器与命令辅助器)
   - [聚合根（集群实体）](#29-聚合根集群实体)
   - [工厂](#210-工厂)
   - [仓储（写侧）](#211-仓储写侧)
   - [应用服务](#212-应用服务)
   - [查询（CQRS 读侧）](#213-查询cqrs-读侧)
   - [读模型与投影](#214-读模型与投影)
   - [持久化适配器（防腐层）](#215-持久化适配器防腐层)
   - [组合根 / 启动装配](#216-组合根--启动装配)
   - [表现层](#217-表现层)
3. [端到端追溯](#3-端到端追溯)
4. [文件数量汇总](#4-文件数量汇总)
5. [分层测试](#5-分层测试)

---

## 1. 依赖规则（洋葱架构）

依赖只能**向内**。领域层完全不知道 Akka 持久化、Play 或 MySQL 的存在。

```
            ┌────────────────────────────────────────────┐
            │  表现层（controllers、views）                │
            │  ┌──────────────────────────────────────┐  │
            │  │  应用层（services、queries、          │  │
            │  │  projections、聚合装配）               │  │
            │  │  ┌────────────────────────────────┐  │  │
            │  │  │  领域层（entities、values、      │  │  │
            │  │  │  invariants、领域服务）          │  │  │
            │  │  └────────────────────────────────┘  │  │
            │  └──────────────────────────────────────┘  │
            │  基础设施层（适配器实现应用/领域的 SPI）       │
            └────────────────────────────────────────────┘
```

通过包结构机械化地约束：

| 层 | 包 | Scala 文件数 | 可依赖 |
|---|---|---|---|
| 领域层 | `app/net/imadz/domain` | **13** | 仅 `common-core` 抽象（`Money` 只用 `java.util.Currency`；规则用 `common-core` 的 `InvariantRule` SPI） |
| 应用层 | `app/net/imadz/application` | **22** | 领域层 + Akka Typed（actor、分片）+ saga-core DSL |
| 基础设施层 | `app/net/imadz/infrastructure`、`app/modules` | **12** | 应用层定义的 SPI + 具体技术（Mongo、Protobuf、ScalikeJDBC） |
| 表现层 | `app/controllers`、`app/views` | **3 + 3** | 应用服务与查询 |
| 共享内核 | `common-core`（独立模块） | — | 仅 Akka Typed |

领域层对 `akka.persistence`、`play.api`、任何驱动程序的导入数为 **0**——事件溯源机制位于应用层的聚合装配中，序列化位于基础设施适配器中。

---

## 2. 概念逐个落地

### 2.1 值对象

> 无标识、完全由属性定义的不可变对象；按值判等。

**实现（1 个文件）**——`app/net/imadz/domain/values/Money.scala`

```scala
case class Money(amount: BigDecimal, currency: Currency)
```

亮点在于非法运算**不可表示而非抛异常**：`+`、`-`、`<=` 都返回 `Option`，币种不一致时得到 `None`。领域规则中的调用方必须显式处理跨币种分支——没有运行时惊吓。

`Money` 同时出现在命令、事件和状态中（见下文），由 protobuf 事件适配器负责序列化。

### 2.2 领域事件

> 已发生事实的不可变记录，以过去时态命名；是写侧唯一的"事实账本"。

**实现（定义于 1 个文件）**——`app/net/imadz/domain/entities/CreditBalanceEntity.scala`

七个事件，全部为 case class：

| 事件 | 含义 |
|---|---|
| `BalanceChanged(update, timestamp)` | 某币种余额变化（+ 存款 / − 取款） |
| `FundsReserved(transferId, amount)` | 转账资金预留（付款方 TCC *Try*） |
| `FundsDeducted(transferId, amount)` | 预留资金最终扣减（付款方 TCC *Confirm*） |
| `ReservationReleased(transferId, amount)` | 预留回滚（付款方 TCC *Cancel*） |
| `IncomingCreditsRecorded(transferId, amount)` | 登记入账（收款方 TCC *Try*） |
| `IncomingCreditsCommited(transferId)` | 入账转入余额（收款方 TCC *Confirm*） |
| `IncomingCreditsCanceled(transferId)` | 入账回滚（收款方 TCC *Cancel*） |

事件是层间的契约：事件处理器把它变成状态，投影把它变成读模型，protobuf 适配器把它变成字节。注意事件命名携带 TCC 词汇——这个聚合从第一天起就是为 Saga 设计的。

### 2.3 聚合状态

> 聚合的全部当前事实，由事件折叠重建。

**实现（同一文件）**——`CreditBalanceEntity.scala`

```scala
case class CreditBalanceState(
  userId: String,
  accountBalance: Map[String, Money],       // 币种 -> 余额
  reservedAmount: Map[Id, Money],           // transferId -> 已预留（付款方）
  incomingCredits: Map[Id, Money]           // transferId -> 待入账（收款方）
)
```

`CreditBalanceEntity.empty(userId)` 是新聚合的工厂。因为 TCC 的未决工作（预留、待入账）就**放在状态里**，崩溃后无需外部事务协调器即可恢复——Saga 引擎重放自己的日志和这些聚合的日志，就能继续。

### 2.4 事件处理器（状态演进）

> 定义每个事件"意味着什么"的纯函数 `(State, Event) => State`。

**实现（1 个文件）**——`app/net/imadz/domain/entities/behaviors/CreditBalanceEventHandler.scala`

值得注意的规则：

- `FundsReserved` 同时**扣减余额并登记预留**（钱是"被挪出来"，不是消失）。
- `FundsDeducted` 只移除预留——钱在预留时已离开余额（这正是 TCC *Try* 安全的原因）。
- `ReservationReleased` 把金额加回并删除预留。
- `IncomingCreditsCommited` 把待入账转入余额；`…Canceled` 只删除。

纯函数使它成为代码库中最易测试的构件：没有 actor、没有 I/O。

### 2.5 不变式规则

> 事件被发出**之前**必须成立的业务规则——聚合的事务边界。本仓库把它建模为一等公民的可组合对象，而不是散落的 `if`。

**实现（9 个文件）**——`app/net/imadz/domain/invariants/`

所有规则实现 `common-core` 的 `InvariantRule[Event, State, P]` SPI：给定当前状态和参数，返回 `Either[iMadzError, List[Event]]`——即**决定哪些事件允许被追加**。

| 规则文件 | 守护的业务规则 | 产生事件 | 错误码 |
|---|---|---|---|
| `AddInitialOnlyOnceRule` | 仅当所有币种余额为零/空时可初始入账 | `BalanceChanged` | 60000 |
| `DepositRule` | 存款金额必须为正 | `BalanceChanged` | 60001 |
| `WithdrawRule` | 金额为正且余额充足 | `BalanceChanged`（负向） | 60002 |
| `ReserveFundsRule` | 委托 `TransferDomainService`；重复预留（60008）作为幂等成功 `Right(Nil)`——不产生事件 | `FundsReserved` | 60003/60004 |
| `DeductFundsRule` | 预留必须存在 | `FundsDeducted` | 60006 |
| `ReleaseReservedFundsRule` | 预留必须存在 | `ReservationReleased` | 60006 |
| `RecordIncomingCreditsRule` | 同一 transferId 不得重复登记 | `IncomingCreditsRecorded` | 60007 |
| `CommitIncomingCreditsRule` | 必须先登记才能提交 | `IncomingCreditsCommited` | 60008 |
| `CancelIncomingCreditRule` | 必须先登记才能取消 | `IncomingCreditsCanceled` | 60009 |

错误码构成稳定的业务错误词汇表（`iMadzError`），Saga 参与者随后把它们分类为*可重试 / 不可重试*失败——这是 DDD 与 Saga 引擎之间的黏合点。

### 2.6 领域服务

> 不天然属于单个实体的无状态业务逻辑。

**实现（1 个文件）**——`app/net/imadz/domain/services/TransferDomainService.scala`

`validateTransfer(transferId, reservedAmount, fromBalance, amount)` 校验：不可重复预留（60008）、余额充足（60003）、金额为正（60004）。`ReserveFundsRule` 组合使用它——体现了规则/服务的分工：规则负责编排，服务负责计算。

### 2.7 命令与协议

> 聚合的公开消息 API：命令进、确认出。

**实现（1 个文件）**——`app/net/imadz/application/aggregates/CreditBalanceProtocol.scala`

- **10 个命令**：`AddInitial`、`Deposit`、`Withdraw`、`GetBalance`，以及六个 TCC 命令 `ReserveFunds` / `DeductFunds` / `ReleaseReservedFunds` / `RecordIncomingCredits` / `CommitIncomingCredits` / `CancelIncomingCredits`（都以 `transferId` 为键）。
- **回复**：`CreditBalanceConfirmation(error, balances)` 及各命令确认。
- 声明了聚合使用的 `CreditBalanceCommandHandler` 类型别名。

这份协议是 Saga 参与者编程所依赖的*契约*（见 [Saga 指南 §代码上手](SAGA_GUIDE-zh.md#代码上手四步)）。

### 2.8 命令处理器与命令辅助器

> 命令与规则的交汇点：校验 → 持久化事件 → 回复。

**实现（2 个文件）**——`app/net/imadz/application/aggregates/behaviors/`

- `CreditBalanceBehaviors.scala` 把十个命令组织成三组处理器——*Direct*（AddInitial/Deposit/Withdraw/GetBalance）、*Reserve*、*IncomingCredit*——每组跑同一个模板：`runReplyingPolicy(Rule, Helper)`。
- `CreditBalanceCommandHelpers.scala` 包含九个 `CommandHelper` 实例——负责"填空"：把命令映射为规则的参数、把 `Right(events)` / `Left(error)` 映射回回复。新增一个命令恰好需要改动：协议 + 辅助器 + 行为 + 一个规则。

这层间接让十个命令保持统一的形状（校验/持久化/回复单一代码路径），代价是多一个文件——这是在 `knowledge_base/artifacts/` 中明确记录的权衡。

### 2.9 聚合根（集群实体）

> 一致性边界，通过稳定 id 可达——这里是集群分片的事件溯源 Akka Typed 实体。

**实现按设计横跨两层：**

| 部分 | 文件 | 层 |
|---|---|---|
| 实体类型键、事件标签（`credit-balance-0..4`）、实体行为组合 | `app/net/imadz/application/aggregates/CreditBalanceAggregate.scala` | 应用层 |
| 分片初始化、`EventSourcedBehavior` 配置（每 100 事件快照、持久化失败退避、事件/快照适配器、tagger） | `app/net/imadz/infrastructure/bootstrap/CreditBalanceBootstrap.scala` | 基础设施层 |

领域状态（§2.3）与规则（§2.5）保持纯净；*actor* 只是应用层包在外面的壳，其 Akka 配置属于基础设施。

### 2.10 工厂

> 创建聚合实例 / 实体引用。

**实现（1 个文件）**——`app/net/imadz/application/aggregates/factories/CreditBalanceAggregateFactory.scala`

`CreateCreditBalanceService` 用它开新户（发出 `AddInitial`），仓储用它获取 `EntityRef`。

### 2.11 仓储（写侧）

> 把"按 id 取聚合"抽象为应用层拥有的接口。

**实现（2 个文件，接口 + 适配器）：**

- 接口：`app/net/imadz/application/aggregates/repository/CreditBalanceRepository.scala` —— `findCreditBalanceByUserId(id): EntityRef[CreditBalanceCommand]`
- 实现：`app/net/imadz/infrastructure/repositories/aggregate/CreditBalanceRepositoryImpl.scala` —— `ClusterSharding.entityRefFor(...)`

经典 DDD 仓储返回*物化对象*；在 Akka 系统里"物化"是一个通往（可能休眠的）分片实体的消息通道——抽象在翻译后依然成立。

### 2.12 应用服务

> 用例编排：每个业务事务一个公开方法；自身不含业务规则。

**实现（4 个文件）**——`app/net/imadz/application/services/`

| 服务 | 用例 | 交互对象 |
|---|---|---|
| `CreateCreditBalanceService` | 开户（可带初始存款） | 工厂 → 聚合 |
| `DepositService` | 存款 | 仓储 → `Deposit` 命令 |
| `WithdrawService` | 取款 | 仓储 → `Withdraw` 命令 |
| `MoneyTransferService` | 跨账户转账 | `MoneyTransferSagaDefinition.runner`（TCC Saga）——按 `txId` 幂等，返回带完成 Future 的 `TransferSubmission` 及 `statusOf(txId)` 轮询 |

前三个只是 ask() 周围的一行封装；转账服务把*进程内编排*换成了 Saga 引擎——DDD 与分布式事务的交汇点。

### 2.13 查询（CQRS 读侧）

> 查询不经过规则和事件；要么打聚合实时状态，要么打读模型。

**实现（2 个文件）**——`app/net/imadz/application/queries/`

- `GetBalanceQuery` —— ask 聚合的 `GetBalance` 命令：**强一致**（分片实体的内存状态）。
- `GetRecent12MonthsIncomeAndExpenseReport` —— 查询 MySQL 读侧仓储：**设计上最终一致**。

### 2.14 读模型与投影

> 从事件流物化的反规范化视图。

**实现（4 个文件）**——`app/net/imadz/application/projection/`

| 文件 | 消费 | 产出 | 语义 |
|---|---|---|---|
| `MonthlyIncomeAndExpenseSummaryProjection` | Mongo 读 journal、tag `credit-balance-0..4` | MySQL `monthly_income_and_expense_summary` | exactly-once（`JdbcProjection.exactlyOnce`，ScalikeJDBC 会话） |
| `MonthlyIncomeAndExpenseSummaryProjectionHandler` | `BalanceChanged`（±）、`FundsDeducted`（−） | 用户 × 月份的收支行 | — |
| `SagaBusinessEventProjection` | 协调器 journal tag | 解析 Saga 的 `onResult` 业务事件 → 事件流 | at-least-once；下游按 `txId` 去重 |
| `repository/MonthlyIncomeAndExpendsSummaryRepository` | — | 读侧仓储 trait + 表模型 | — |

基于 tag 的分发（5 个 tag）让 5 个投影实例得以并行运行（ShardedDaemonProcess）。

### 2.15 持久化适配器（防腐层）

> 把领域的词汇翻译成数据库的字节——并让领域对两者保持无知。

**实现（3 个文件）**——`app/net/imadz/infrastructure/persistence/`（含 `converters/`）

- `CreditBalanceEventAdapter` / `CreditBalanceSnapshotAdapter` —— 领域事件/状态与 protobuf `CreditBalanceEventPO` / `CreditBalanceStatePO` 之间的 Akka `EventAdapter`。
- `CreditBalanceProtoConverters` —— 字段级映射（schema 在 protobuf 定义中，journal 因此有显式、可演化的线上格式）。

### 2.16 组合根 / 启动装配

> 启动时把一切装配起来——唯一允许了解全局的地方。

**实现（6 个文件）**

- `app/net/imadz/infrastructure/bootstrap/ApplicationBootstrap.scala` —— 有序启动：① 注册 Saga 定义 → ② 初始化 CreditBalance 分片 → ③ 初始化 Saga 引擎（所有定义共享一套协调器分片）→ ④ Saga 业务事件投影 → ⑤ 月度汇总投影。
- `bootstrap/SagaEngineBootstrap.scala`、`bootstrap/SagaBusinessEventProjectionBootstrap.scala`、`bootstrap/MonthlyIncomeAndExpenseBootstrap.scala` —— 各启动步骤。
- `infrastructure/SuffixCollectionNames.scala` —— Mongo 集合命名策略。
- `app/modules/BootstrapModule.scala` —— Guice 模块，将 `ApplicationBootstrap` 绑定为 eager singleton。

### 2.17 表现层

**实现（6 个文件）**——`app/controllers/`（2）、`app/controllers/filter/LoggingFilter.scala`（1）、`app/views/`（3 个 Twirl 模板，含 Saga Showcase 单页 UI），以及 `conf/routes`。

控制器保持单薄：解析请求 → 调应用服务或查询 → 序列化回复。`ShowcaseController` 额外持有 WebSocket hub，发布 `SagaProgressEvent`。

---

## 3. 端到端追溯

**"存 100 元 CNY"经过：**

`HomeController.deposit` → `DepositService` → `CreditBalanceRepository`（接口）→ `CreditBalanceRepositoryImpl` → 分片 `EntityRef` → `CreditBalanceBehaviors`（Direct 组）→ `CreditBalanceCommandHelpers.DepositHelper` → `DepositRule` → 事件 `BalanceChanged` → `CreditBalanceEventHandler` →（异步）`MonthlyIncomeAndExpenseSummaryProjectionHandler` → MySQL 行。

**"A 向 B 转 10 元"经过：**

`HomeController.transfer` → `MoneyTransferService` → `MoneyTransferSagaDefinition`（`preCheck` → 启动 Saga）→ Saga 协调器 → `FromAccountParticipant`（`ReserveFunds`/`DeductFunds`/`ReleaseReservedFunds`，走与上面相同的规则）+ `ToAccountParticipant`（`Record`/`Commit`/`Cancel` 入账）→ 完成后 `SagaBusinessEventProjection` 解析 `onResult` → `MoneyTransferCompleted` 业务事件。

**"修复一笔卡住的 Saga"经过：**

`ShowcaseController.fixStep`/`resume` → `SagaRunner.admin` → 协调器 `ManualFixStep`/`ResolveSuspended` → 持久化 `StepManuallyFixed` → 阶段重驱并跳过已修复步骤（见 [Saga 指南 §人工干预](SAGA_GUIDE-zh.md#人工干预)）。

---

## 4. 文件数量汇总

| DDD 概念 | 文件数 | 位置 |
|---|---:|---|
| 值对象 | 1 | `domain/values` |
| 领域事件 + 聚合状态 | 1 | `domain/entities` |
| 事件处理器 | 1 | `domain/entities/behaviors` |
| 不变式规则 | 9 | `domain/invariants` |
| 领域服务 | 1 | `domain/services` |
| 命令/协议 | 1 | `application/aggregates` |
| 命令处理器 + 辅助器 | 2 | `application/aggregates/behaviors` |
| 聚合根装配 | 1 | `application/aggregates`（另有 1 个基础设施 bootstrap） |
| 工厂 | 1 | `application/aggregates/factories` |
| 仓储（写侧） | 2 | `application/.../repository` + `infrastructure/repositories/aggregate` |
| 应用服务 | 4 | `application/services`（`transactor/` 另有 6 个） |
| 查询 | 2 | `application/queries` |
| 读模型 / 投影 | 4 | `application/projection`（另有 1 个基础设施 bootstrap） |
| 持久化适配器 | 3 | `infrastructure/persistence` |
| 组合根 | 6 | `infrastructure/bootstrap`、`modules` |
| 表现层 | 6 | `controllers`、`views` |
| **领域层合计** | **13** | |
| **应用层合计** | **22** | |
| **基础设施层合计** | **11（+1 modules）** | |

## 5. 分层测试

| 层 | 测试载体 | 风格 |
|---|---|---|
| 领域规则/状态 | `test/net/imadz/banking/.../CreditBalanceCommandHelpersSpec`（+ `CommandHelperTestKit`） | 纯函数断言 |
| Saga 引擎 | `saga-core/src/test` —— 53 个用例，AC-1.1…AC-1.12 + AC-MF | 基于持久化测试套件（内存 journal）的验收测试 |
| 整个应用 | `sbt acceptance`（= `test`） | 门禁别名；任何失败即阻断构建 |
