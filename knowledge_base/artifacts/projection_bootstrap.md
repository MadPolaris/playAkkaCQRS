# Artifact: Projection Bootstrap (投影引导)

**Target**: `ProjectionBootstrapArtifact`
**File Pattern**: `infrastructure/bootstrap/${Name}Bootstrap.scala`

## 1. 核心职责
使用 Akka Projection 的 `ShardedDaemonProcess` 在集群中运行读端处理器。

## 2. 编码约束
1.  **Dependencies**: 需要注入 `ActorSystem`, `ClusterSharding` 和对应的 `Repository`。
2.  **Daemon Init**:
    -   使用 `ShardedDaemonProcess(system).init(...)`。
    -   `numberOfInstances` 必须与聚合的 tags 数量一致 (通常为 5)。
3.  **Factory Call**: 调用应用层定义的 `Projection.createProjection(...)`。

## 3. 参考模板
```scala
trait MonthlySummaryBootstrap {
  def initMonthlySummary(system: ActorSystem[_], repo: MonthlySummaryRepository): Unit = {
    ShardedDaemonProcess(system).init(
      name = "MonthlySummary",
      numberOfInstances = 5,
      behaviorFactory = n => ProjectionBehavior(MonthlySummaryProjection.createProjection(..., n, repo))
    )
  }
}
```