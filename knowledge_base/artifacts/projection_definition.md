# Artifact: Projection Definition (投影定义)

**Target**: `ProjectionDefinitionArtifact`
**File Pattern**: `application/projection/${Name}Projection.scala`

## 1. 核心职责
定义 Akka Projection 的拓扑结构，将 Source (Journal) 连接到 Handler。

## 2. 编码约束
1.  **SourceProvider**: 使用 `EventSourcedProvider.eventsByTag`。
2.  **Projection Type**: 使用 `JdbcProjection.exactlyOnce` 以保证数据一致性。
3.  **Session**: 使用 `ScalikeJdbcSession`。
4.  **Factory Method**: 提供 `createProjection` 方法，供 Bootstrap 调用。

## 3. 参考模板
```scala
object MonthlySummaryProjection {
  def createProjection(system: ActorSystem[_], ...): ExactlyOnceProjection[...] = {
    val source = EventSourcedProvider.eventsByTag(...)
    JdbcProjection.exactlyOnce(
      projectionId = ProjectionId("MonthlySummary", tag),
      sourceProvider = source,
      handler = () => new MonthlySummaryHandler(...)
    )(system)
  }
}
```