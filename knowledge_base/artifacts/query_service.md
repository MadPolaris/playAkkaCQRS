# Artifact: Query Service (查询服务)

**Target**: `QueryServiceArtifact`
**File Pattern**: `application/queries/${Name}.scala`

## 1. 核心职责
实现 CQRS 中的 **Read Side** 逻辑。专门用于高效数据查询，绕过聚合根，直接访问读库。

## 2. 编码约束
1.  **Read-Only**: 严禁在查询服务中修改数据。
2.  **Source**: 依赖 `ProjectionRepository` (Read-Side DB) 而非 `AggregateRepository`。
3.  **Return Type**: 返回专门的 DTO (Read Model) 或基础类型，而非 Domain Entity。
4.  **Performance**: 允许针对查询场景进行特定优化。

## 3. 参考模板
```scala
class GetBalanceQuery @Inject()(repo: MonthlySummaryRepository) {
  def getSummary(id: String): Future[SummaryDTO] = repo.findByUserId(id)
}
```