# Artifact: Domain Entity (实体定义)

**Target**: `DomainEntityArtifact`
**File Pattern**: `domain/entities/${Name}Entity.scala`

## 1. 核心职责
定义聚合根的数据结构（State）和事实（Events）。这是纯粹的领域模型，不包含行为逻辑。

## 2. 编码约束
1.  **Purity**: 纯 Scala 代码。**严禁**引入 Akka、Play、JSON 或 DB 相关的包。
2.  **File Structure**:
    -   `object ${Name}Entity`: 伴生对象，包含工厂方法。
    -   `case class ${Name}State`: 聚合状态，必须包含 `id`。
    -   `sealed trait ${Name}Event`: 领域事件的父接口。
    -   `type ${Name}EventHandler`: 领域事件处理器的 Type Alias 定义
3.  **State**: 必须提供 `empty(id: Id)` 方法来初始化空白状态。
4.  **Events**: 所有 Event 必须是 `case class` 并继承父 trait。

## 3. 参考模板
```scala
object CreditBalanceEntity {
  case class CreditBalanceState(...)
  sealed trait CreditBalanceEvent
  case class BalanceChanged(...) extends CreditBalanceEvent
  // Event Handler Extension Point
  type CreditBalanceEventHandler = (CreditBalanceState, CreditBalanceEvent) => CreditBalanceState
  def empty(id: Id): CreditBalanceState = ...
}
```