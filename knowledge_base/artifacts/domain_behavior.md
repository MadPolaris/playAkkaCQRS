# Artifact: Domain Behavior (领域行为)

**Target**: `DomainBehaviorArtifact`
**File Pattern**: `domain/entities/behaviors/${Name}EventHandler.scala`

## 1. 核心职责
实现事件溯源中的 **"Apply"** 逻辑。即：给定旧状态和新事件，计算出新状态。

## 2. 编码约束
1.  **Signature**: `(State, Event) => State`。
2.  **Purity**: 绝对纯函数。**严禁**副作用（如打印日志、调用外部服务、生成随机数）。
3.  **Completeness**: 必须处理 Metadata 中定义的所有 `EventStateMutation`。
4.  **Logic**: 仅负责数据的 Copy 和 Update，不负责业务规则校验（校验在 Command Handler 中）。

## 3. 参考模板
```scala
object CreditBalanceEventHandler {
  def apply: (State, Event) => State = (state, event) => event match {
    case BalanceChanged(amount) => state.copy(balance = state.balance + amount)
  }
}
```