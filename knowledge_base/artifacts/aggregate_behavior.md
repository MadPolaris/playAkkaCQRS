# Artifact: Aggregate Behaviors (聚合核)

**Target**: `AggregateCommandBehaviorArtifact`
**File Pattern**: `application/aggregates/behaviors/${Name}Behaviors.scala`

## 1. 核心职责
实现命令处理逻辑 (Command Handler)。决定接受命令（生成事件）还是拒绝命令（返回错误）。

## 2. 编码约束
1.  **Composition Pattern**: 使用 `PartialFunction` 将不同业务块组合（如 `direct.orElse(reserve)`）。
2.  **Domain Policy DSL**:
    -   **严禁**在 Behavior 中编写裸露的 `if/else` 业务判断。
    -   **必须**调用 `domain.policy` 下的策略对象。
    -   **语法**: `runReplyingPolicy(DepositPolicy)(state, cmd).replyWith(...)`。
3.  **Dependencies**: 仅依赖 `DomainEntity`, `DomainPolicy` 和 `DomainService`。

## 3. 参考模板
```scala
object CreditBalanceBehaviors {
  def apply: CommandHandler = (state, cmd) => direct(state).orElse(reserve(state)).apply(cmd)
  
  private def direct(state: State) = {
    case Deposit(amount, replyTo) =>
      runReplyingPolicy(DepositPolicy)(state, amount)
        .replyWith(replyTo)(mkError, mkSuccess)
  }
}
```