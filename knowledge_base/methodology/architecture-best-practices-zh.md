# 架构最佳实践：模式背后的"为什么"

## 1. 洋葱架构：边界使并行成为可能

### 规则
```
domain/          ← 纯领域层：零外部依赖，纯函数为主
application/     ← 用例层：只依赖 domain
infrastructure/  ← 适配层：依赖所有内层，外部框架只在此层
```

**铁律：** 内层绝不导入外层包。`domain/` 文件导入 `akka.*` 就是违规。

### 为什么重要

这不是为了美学。这是为了**无需加锁的并行工作。**

当依赖方向被严格执行时：
- 写 `domain/invariants/DepositRule.scala` 的人永远不会和写 `infrastructure/persistence/CreditBalanceEventAdapter.scala` 的人编辑同一个文件
- 层变成了**同步边界**。没有人等待别人。
- 每层可以独立测试：domain 用纯单元测试，application 用 Actor 测试套件，infrastructure 用集成测试。

在任务雷达方法论中，这意味着 10 个人可以从不同层领 10 个任务同时开工。层的边界就是合约。

### 代价

更多文件。更多间接性。一个简单功能跨 3 层涉及 5-7 个文件。这是故意的：每个文件小到可以装入 AI 上下文窗口，实现局部推理。

---

## 2. 接口驱动开发：合约取代协调

### 模式

每个组件在实现之前先暴露 trait（接口）：

```
application/aggregates/repository/CreditBalanceRepository.scala  ← trait（合约）
infrastructure/repositories/aggregate/CreditBalanceRepositoryImpl.scala ← class（实现）
```

同样的模式适用于：AggregateFactory、ProjectionRepository、SagaParticipant、DomainService、ApplicationService。

### 为什么重要

接口是**消除协调成本的合约。**

在任务雷达工作流中：
1. 一个人定义接口（签名 + 语义）
2. 十个人对着接口实现——并行，不需要互相沟通
3. 接口是他们唯一需要达成一致的东西

这和依赖注入是同一个原则，只不过应用在团队层面。接口就是队友之间的 API。

对 AI Agent 来说：一个有清晰签名和文档化语义的 trait 就是**函数规格说明**。Agent 实现函数体；签名保证兼容性。

---

## 3. Artifact 分解：小文件 = 局部推理

### 模式

本来可以是一个大类的东西被拆成 9+ 个文件：

| Artifact | 文件 | 层 |
|----------|------|-----|
| Domain Entity | `CreditBalanceEntity.scala` | domain |
| Event Handler | `CreditBalanceEventHandler.scala` | domain |
| Aggregate Protocol | `CreditBalanceAggregate.scala` | application |
| Command Behaviors | `CreditBalanceBehaviors.scala` | application |
| Command Helpers | `CreditBalanceCommandHelpers.scala` | application |
| Aggregate Factory | `CreditBalanceAggregateFactory.scala` | application |
| Repository Trait | `CreditBalanceRepository.scala` | application |
| Bootstrap | `CreditBalanceBootstrap.scala` | infrastructure |
| Repository Impl | `CreditBalanceRepositoryImpl.scala` | infrastructure |

每个文件约 20-80 行。每个文件有单一职责。

### 为什么重要

这种分解是为 AI 上下文窗口而设计的：

- **小上下文 = 精确推理。** 修复 `WithdrawRule` bug 的 Agent 不需要加载 `CreditBalanceBehaviors`。不变量规则是 `(State, Param) => Either[Error, List[Event]]` 的纯函数——可以完全隔离测试。
- **无溢出效应。** 修改事件处理器不会意外破坏命令协议，因为它们在不同的文件中，有不同的导入依赖。
- **每个文件对应一个 artifact 模板。** `knowledge_base/artifacts/` 中的 30 个文件与代码中出现的 artifact 类型一一对应。

代价是人类浏览代码的认知成本。收益是 AI Agent（和人类）可以对任何单个文件做局部推理。

---

## 4. Protobuf "三件套"规则

### 规则

修改以下任意一个时，必须同步修改另外两个：

1. **Scala Case Class**（`domain/` 或 `application/` 层）
2. **`.proto` 文件**（`app/protobuf/`）
3. **Proto Converter**（`toProto` / `fromProto`，在 `infrastructure/persistence/converters/`）

### 为什么重要

序列化是 CQRS/Event Sourcing 系统中风险最高的环节。当事件被持久化到 MongoDB 然后回放时，反序列化后的形式必须与序列化时逐字节一致。不匹配意味着**事件日志损坏**——数据丢失。

三件套规则让不同步问题在**编译时**暴露，而非在事件回放的运行时才暴露。如果你给 Scala case class 加了字段但忘了改 `.proto`，converter 编译不过。编译器强制执行一致性。

对 AI Agent：这条规则在 CLAUDE.md 中显式声明，因为 Agent 倾向于改一个文件而忘了另外两个。规则强制它们检查全部三个。

---

## 5. 函数式编程模式

### 用 Either 表达错误，不用异常

```scala
// 领域不变量返回 Either，从不 throw
def apply(state: State, param: Money): Either[iMadzError, List[Event]]
```

**为什么：** 错误路径在类型签名中是显式的。编译器强制调用者处理成功和失败两种情况。在 Event Sourcing 中，事件回放时的未处理错误 = 不可恢复的状态。Either 让错误路径可见。

### 不可变数据结构

```scala
// 状态通过 copy 演化，永不直接修改
state.copy(accountBalance = state.accountBalance + (currency -> newBalance))
```

**为什么：** Event Sourcing 通过回放事件重建状态。如果状态是可变的，回放可能在不同运行中产生不同结果。不可变性保证确定性回放。

### Domain 层的纯函数

```scala
// 事件处理器：(State, Event) => State — 无副作用，无 IO，无外部调用
type CreditBalanceEventHandler = (CreditBalanceState, CreditBalanceEvent) => CreditBalanceState
```

**为什么：** 依赖数据库查询或 HTTP 调用的领域逻辑无法隔离测试，也无法在事件回放时信任。`domain/` 中的纯函数保证核心业务规则始终可通过简单的输入/输出断言来测试。

---

## 6. DSL 模式：引导 AI 走向正确代码

### Command Handler DSL

```scala
case cmd: Deposit =>
  runReplyingPolicy(DepositRule, DepositHelper)(state, cmd)
    .replyWithAndPublish(cmd.replyTo)(context)
```

这不只是语法糖。它是一个**结构化模板**，消除了命令处理方式的差异：

1. 从命令中提取参数（`DepositHelper.toParam`）
2. 执行不变量规则（`DepositRule.apply`）
3. 失败：回复错误（`DepositHelper.createFailureReply`）
4. 成功：持久化事件，然后回复成功（`DepositHelper.createSuccessReply`）

**为什么对 Agent 重要：** 没有这个 DSL，Agent 可能会写原始的 `if/else` 加内联错误处理，忘了持久化事件，或者搞混回复类型。DSL 把 Agent 约束到唯一的正确路径。模式如此之强，`CreditBalanceBehaviors.scala` 读起来像声明式的路由表，而非命令式代码。

详见源码：[`CommandHandlerReplyingBehavior.scala`](../../../common-core/src/main/scala/net/imadz/common/application/CommandHandlerReplyingBehavior.scala)

### InvariantRule trait

```scala
trait InvariantRule[Event, State, Param] {
  def apply(state: State, param: Param): Either[iMadzError, List[Event]]
}
```

所有业务验证的单一接口。每一个不变量规则（DepositRule、WithdrawRule、ReserveFundsRule...）遵循完全相同的签名。

**为什么重要：** 当每个规则都有相同的形状时，新增一个就是机械操作。Agent 被告诉"按照模板添加新的不变量规则"，就会产出完美契合系统的代码。类型参数（`Event`、`State`、`Param`）就是一个内置检查清单：你定义了产出什么事件吗？读取什么状态？需要什么参数？

详见源码：[`CommonTypes.scala`](../../../common-core/src/main/scala/net/imadz/common/CommonTypes.scala)

---

## 总结：架构作为力量倍增器

上述每种模式服务于同一个目标：**让正确的事成为容易的事。**

- 洋葱边界 → 无法创建阻碍并行工作的耦合
- 到处是接口 → 无法依赖实现细节
- 小文件 → 无法创建超出 AI 上下文的文件
- 三件套规则 → 无法在重构时忘记序列化
- Either 类型 → 无法忽略错误路径
- DSL 模式 → 无法用错误方式写 command handler

这就是项目架构哲学的本质：**阻止错误发生的约束**，而非**请求遵守的指南**。
