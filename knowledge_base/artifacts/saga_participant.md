# Artifact: Saga Participant (Saga 参与者)

**Target**: `SagaParticipantArtifact`
**File Pattern**: `application/services/transactor/${Name}Participant.scala`

## 1. 核心职责
实现 Saga 流程中的单个原子步骤。它是连接 Saga 引擎与具体业务聚合的适配器。

## 2. 编码约束
1.  **Inheritance**: 必须扩展 `net.imadz.infra.saga.SagaParticipant[iMadzError, String]`。
2.  **Methods**: 必须实现 `doPrepare`, `doCommit`, `doCompensate`。
3.  **Resilience**:
    -   必须重写 `customClassification: PartialFunction[Throwable, RetryableOrNotException]`。
    -   明确区分哪些错误是可重试的（网络抖动），哪些是不可重试的（业务规则失败）。
4.  **Logic**: 通常通过 `repository.find(id).ask(...)` 调用聚合根。

## 3. 参考模板
```scala
class FromAccountParticipant(...) extends SagaParticipant[iMadzError, String] {
  override def doPrepare(tid: String): Future[...] = ...
  override def customClassification: PartialFunction[...] = {
    case e: TimeoutException => RetryableFailure(e.getMessage)
    case e: iMadzError => NonRetryableFailure(e.message)
  }
}
```