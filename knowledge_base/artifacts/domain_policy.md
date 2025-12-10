
### Component: Domain Policy

**Artifact Type**: `DomainPolicyArtifact`
**Layer**: Domain Layer (Layer 1)

**Concept**:
封装业务规则的纯函数。用于在 Aggregate Behavior 中决策“是否可以通过”以及“产生什么事件”。

**Physical File**:
* Path: `domain/policy/${PolicyName}.scala`

**Constraints**:
1.  **Inheritance**: 必须扩展 `net.imadz.common.CommonTypes.DomainPolicy[Event, State, Param]`。
2.  **Signature**: `apply(state, param): Either[iMadzError, List[Event]]`。
3.  **Purity**: 必须是纯函数，无副作用（不查库，不调外部服务）。

**Implementation Pattern**:
```scala
object DepositPolicy extends DomainPolicy[CreditBalanceEvent, CreditBalanceState, Money] {
  def apply(state: CreditBalanceState, amount: Money): Either[iMadzError, List[CreditBalanceEvent]] =
    if (amount.amount <= 0) Left(iMadzError("60001", "Amount must be positive"))
    else Right(List(BalanceChanged(amount)))
}
```

**Testing Strategy (TDD)**
   When writing tests for Domain Policy:
Target Method: Call PolicyName.apply(state, arg).
Assertions:
- Expect Right(expextedEventList) for valid cases.
- Expect Left(iMadzError(code, message)) for invalid cases.
- Do NOT use Some/None or Boolean.
- Naming: Test file should be named ${PolicyName}Spec.