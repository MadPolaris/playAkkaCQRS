# Artifact: Aggregate Repository Interface (聚合仓库接口)

**Target**: `AggregateRepositoryTraitArtifact`
**File Pattern**: `application/aggregates/repository/${Name}Repository.scala`

## 1. 核心职责
定义应用层获取聚合根引用的端口 (Port)。

## 2. 编码约束
1.  **Binding**: 使用 `@ImplementedBy` 注解绑定到 Infra 层实现。
2.  **Return Type**: 返回 `EntityRef[${Name}Command]`。**注意不是 Future**，获取引用本身是同步轻量的。
3.  **Methods**: 通常只需要 `find(id: Id)` 或 `get(id: Id)`。

## 3. 参考模板
```scala
@ImplementedBy(classOf[CreditBalanceRepositoryImpl])
trait CreditBalanceRepository {
  def find(id: Id): EntityRef[CreditBalanceCommand]
}
```