# Artifact: Projection Handler (投影处理器)

**Target**: `ProjectionHandlerArtifact`
**File Pattern**: `application/projection/${Name}Handler.scala`

## 1. 核心职责
实现 Akka Projection 的处理逻辑，将 Event Envelope 映射为数据库写操作。

## 2. 编码约束
1.  **Inheritance**: 继承 `akka.projection.jdbc.scaladsl.JdbcHandler[EventEnvelope[ProtoEvent], ScalikeJdbcSession]`。
2.  **Conversion**: 使用 `EventAdapter` 将 Proto Event 转换回 Domain Event 进行处理（推荐），或直接处理 Proto Event。
3.  **Idempotency**: 依赖 Repository 的 `ON DUPLICATE KEY UPDATE` 逻辑保证幂等性。
4.  **Logic**: `process` 方法中只包含 `match case` 分发逻辑，具体的 SQL 写操作委托给 Repository。

## 3. 参考模板
```scala
class MonthlySummaryHandler(repo: MonthlySummaryRepository) extends JdbcHandler[...] {
  override def process(session: ScalikeJdbcSession, envelope: EventEnvelope[...]): Unit = {
    // extract event, call repo
  }
}
```