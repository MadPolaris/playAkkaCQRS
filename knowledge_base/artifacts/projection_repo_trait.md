# Artifact: Projection Repository Interface (投影仓库接口)

**Target**: `ProjectionRepositoryTraitArtifact`
**File Pattern**: `application/projection/repository/${Name}Repository.scala`

## 1. 核心职责
定义读模型 (Read Model) 的持久化操作接口。

## 2. 编码约束
1.  **Tech Stack**: 针对 JDBC/MySQL 设计接口。
2.  **Async**: 所有 IO 操作必须返回 `Future[T]` (读) 或 `Unit` (写，通常在 Handler 中是同步的，但在 Repository 接口定义中最好保持 Future 或者是同步方法供 Handler 调用，具体取决于 JDBC 上下文。**约定：使用同步方法，因为 ScalikeJDBC 会在 Handler 的 Future 上下文中运行，或者 Handler 本身管理事务**)。
    -   *修正*: 为了配合 `JdbcHandler`，更新方法通常是同步的 (传入 Session)，查询方法是异步的 (`Future`)。
3.  **Methods**: 包含 `upsert`, `update`, `findByXxx`。

## 3. 参考模板
```scala
trait MonthlySummaryRepository {
  // Query
  def find(id: String): Future[Option[Summary]]
  // Command (used by Handler)
  def updateIncome(userId: String, amount: BigDecimal): Unit 
}
```