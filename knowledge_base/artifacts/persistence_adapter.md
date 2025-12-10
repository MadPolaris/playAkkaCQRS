# Artifact: Persistence Adapter (持久化适配器)

**Target**: `PersistenceAdapterArtifact`
**File Pattern**: `infrastructure/persistence/${Name}EventAdapter.scala`

## 1. 核心职责
负责 **Domain Event** (Scala Case Class) 与 **Proto Message** (Java Class) 之间的双向转换。

## 2. 编码约束
1.  **Inheritance**: 继承 `EventAdapter[DomainEvent, ProtoEventWrapper]`.
2.  **Manifest**: 实现 `manifest(event)` 返回类名。
3.  **ToJournal**: 将 Domain Event 包装进 Proto 的 `oneof` 结构中。
4.  **FromJournal**: 从 Proto 解包并还原为 Domain Event。注意处理数据类型转换 (e.g. `BigDecimal` <-> `double` / `String`).

## 3. 参考模板
```scala
class CreditBalanceEventAdapter extends EventAdapter[...] {
  override def toJournal(e: Event): ProtoEvent = e match {
    case BalanceChanged(amt) => ProtoEvent.BalanceChanged(...)
  }
}
```