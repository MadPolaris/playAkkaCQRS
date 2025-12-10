#### C. `projection_repository_impl.md` (拆分自 repository_impl.md)
```markdown
# Artifact: Projection Repository Implementation

**Target**: `ProjectionRepositoryImplArtifact`
**File Pattern**: `infrastructure/repositories/projection/${Name}Impl.scala`

## 1. 核心职责
实现投影仓库接口，使用 JDBC 操作读库。

## 2. 编码约束
1.  **Tech Stack**: ScalikeJDBC。
2.  **Idempotency**: 必须使用 `ON DUPLICATE KEY UPDATE` (MySQL) 确保幂等性。
3.  **Context**: 
    -   读操作 (`find`): 使用 `Future { DB readOnly { ... } }`。
    -   写操作 (`update`): 通常由 Handler 在事务内调用，可以是同步方法，或者接收 `implicit session: DBSession`。
    -   **约定**: 为了配合 `JdbcHandler`，写方法签名通常为 `update(..., implicit session: DBSession)` 或在内部 `DB localTx`。

## 3. 参考模板
```scala
class MonthlySummaryRepositoryImpl extends MonthlySummaryRepository {
  override def updateIncome(id: String, amount: BigDecimal): Unit = {
    DB localTx { implicit session =>
      sql"INSERT ... ON DUPLICATE KEY UPDATE ...".update.apply()
    }
  }
}
```