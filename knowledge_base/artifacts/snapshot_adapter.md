# Artifact: Snapshot Adapter (快照适配器)

**Target**: `SnapshotAdapterArtifact`
**File Pattern**: `infrastructure/persistence/${Name}SnapshotAdapter.scala`

## 1. 核心职责
负责 **Aggregate State** 与 **Proto State** 之间的转换，用于快照存储。

## 2. 编码约束
1.  **Inheritance**: 继承 `SnapshotAdapter[State]`.
2.  **Logic**: 类似于 EventAdapter，但针对的是聚合的 State 对象。
3.  **Mapping**: 确保所有状态字段都被正确映射，特别是 Map 和 List 结构。

## 3. 参考模板
```scala
class CreditBalanceSnapshotAdapter extends SnapshotAdapter[CreditBalanceState] {

  override def toJournal(state: CreditBalanceState): Any = {
    val accountBalance = state.accountBalance.map { case (k, v) =>
      k -> MoneyPO(v.amount.doubleValue, v.currency.getCurrencyCode)
    }
    val reservedAmount = state.reservedAmount.map { case (k, v) =>
      k.toString -> MoneyPO(v.amount.doubleValue, v.currency.getCurrencyCode)
    }
    val incomingCredits = state.incomingCredits.map { case (k, v) =>
      k.toString -> MoneyPO(v.amount.doubleValue, v.currency.getCurrencyCode)
    }
    CreditBalanceStatePO(
      userId = state.userId.toString,
      accountBalance = accountBalance,
      reservedAmount = reservedAmount,
      incomingCredits = incomingCredits
    )
  }

  override def fromJournal(from: Any): CreditBalanceState = from match {
    case po: CreditBalanceStatePO =>
      val accountBalance = po.accountBalance.map { case (k, v) =>
        k -> Money(v.amount, Currency.getInstance(v.currency))
      }
      val reservedAmount = po.reservedAmount.map { case (k, v) =>
        Id.of(k) -> Money(v.amount, Currency.getInstance(v.currency))
      }
      val incomingCredits = po.incomingCredits.map { case (k, v) =>
        Id.of(k) -> Money(v.amount, Currency.getInstance(v.currency))
      }
      CreditBalanceState(
        userId = UUID.fromString(po.userId),
        accountBalance = accountBalance,
        reservedAmount = reservedAmount,
        incomingCredits = incomingCredits
      )
    case unknown => throw new IllegalStateException(s"Unknown journal type: ${unknown.getClass.getName}")
  }
}
```