# Artifact: Invariant Rule (不变量规则)

**Target**: `InvariantRuleArtifact`
**File Pattern**: `domain/invariants/${Name}Rule.scala`

## 1. 核心职责
封装业务不变量守护规则。在 Aggregate Behavior 中决策"是否可以通过"以及"产生什么事件"。

## 2. 编码约束
1.  **Inheritance**: 必须扩展 `net.imadz.common.CommonTypes.InvariantRule[Event, State, Param]`。
2.  **Signature**: `apply(state, param): Either[iMadzError, List[Event]]`。
3.  **Purity**: 必须是纯函数，无副作用（不查库，不调外部服务）。

## 3. 参考模板
```scala
object DepositRule extends InvariantRule[CreditBalanceEvent, CreditBalanceState, Money] {
  def apply(state: CreditBalanceState, amount: Money): Either[iMadzError, List[CreditBalanceEvent]] =
    if (amount.amount <= 0) Left(iMadzError("60001", "Amount must be positive"))
    else Right(List(BalanceChanged(amount)))
}
```

## 4. 测试策略 (TDD)
When writing tests for Invariant Rule:
- **Target Method**: Call `RuleName.apply(state, arg)`.
- **Assertions**:
  - Expect `Right(expectedEventList)` for valid cases.
  - Expect `Left(iMadzError(code, message))` for invalid cases.
  - Do NOT use `Some/None` or `Boolean`.
- **Naming**: Test file should be named `${RuleName}Spec`.

## 5. DDD 概念说明
| 概念 | 说明 |
|------|------|
| **Invariant (不变量)** | 业务守护规则，验证状态并决定是否产生事件 |
| **Policy (策略)** | 纯计算逻辑，如折扣计算、路由选择（本 artifact 不覆盖） |
