# Artifact: Aggregate Protocol (聚合壳)

**Target**: `AggregateProtocolArtifact`
**File Pattern**: `application/aggregates/${Name}Aggregate.scala`

## 1. 核心职责
定义外界与聚合交互的**契约 (Interface)**。它是 Akka Actor 的“壳”。

## 2. 编码约束
1.  **Components**:
    -   `sealed trait Command` (继承 `CborSerializable`)。
    -   `case class Reply` (继承 `CborSerializable`)。
    -   `type ${name}CommandHandler = (${name}State, ${name}Command) => Effect[${name}Event, ${name}State]` 命令处理器类型别名
    -   `def commandHandler` 定义
    -   `EntityTypeKey` 和 `tags` 定义。
2.  **Reply Pattern**:
    -   必须遵循标准回执格式：`case class ${Name}Confirmation(error: Option[iMadzError], data: ...)`。
    -   严禁使用简单的 `Boolean` 或 `String` 作为回复。
3.  **No Logic**: 这里**只定义类型**。`commandHandler` 必须直接委托给 `Behaviors.apply`。

## 3. 参考模板
```scala
object CreditBalanceAggregate {
  // Commands
  sealed trait Command extends CborSerializable
  case class Deposit(amount: Money, replyTo: ActorRef[Confirmation]) extends Command
  // Command Replies
  case class Confirmation(error: Option[iMadzError], ...) extends CborSerializable
  // Command Handler
  type CreditBalanceCommandHandler = (CreditBalanceState, CreditBalanceCommand) => Effect[CreditBalanceEvent, CreditBalanceState]

  val TypeKey = EntityTypeKey[Command]("CreditBalance")
  
  def commandHandler = CreditBalanceBehaviors.apply // Delegate!
}
```