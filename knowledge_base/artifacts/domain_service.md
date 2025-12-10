# Artifact: Domain Service (领域服务)

**Target**: `DomainServiceArtifact`
**File Pattern**: `domain/services/${Name}.scala`

## 1. 核心职责
封装那些不属于单一实体或值对象的领域逻辑。通常涉及多个实体的协调或复杂计算。

## 2. 编码约束
1.  **Purity**: 必须是纯 Scala 代码。**严禁**引入 Akka, Future, DB 或外部 I/O。
2.  **Stateless**: 领域服务应该是无状态的 `object` 或 `class`。
3.  **Error Handling**: 业务逻辑错误必须返回 `Either[iMadzError, T]`。
4.  **Input/Output**: 参数和返回值必须是领域对象（Entities, Value Objects）或基础类型。

## 3. 参考模板
```scala
object TransferDomainService {
  def validateTransfer(from: Account, to: Account, amount: Money): Either[iMadzError, Unit] = {
    if (from.balance < amount) Left(iMadzError("InsufficentFunds", "..."))
    else Right(())
  }
}
```