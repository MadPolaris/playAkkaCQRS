# Artifact: Application Service (应用服务)

**Target**: `ApplicationServiceArtifact`
**File Pattern**: `application/services/${Name}Service.scala`

## 1. 核心职责
系统的用例层 (Use Case)。负责编排领域对象、调用仓库、处理事务边界。

## 2. 编码约束
1.  **Async**: 所有公开方法必须返回 `Future[T]`。
2.  **Thin Layer**:
    -   不要在这里写核心业务逻辑（那是 Domain Service 的事）。
    -   主要负责：加载聚合 -> 调用聚合方法 -> 转换结果。
3.  **DI**: 使用 `@Inject` 注入 Repositories 或 Factories。
4.  **Context**: 使用 `Implicit ExecutionContext`。

## 3. 参考模板
```scala
class DepositService @Inject()(repo: CreditBalanceRepository)(implicit ec: ExecutionContext) {
  def deposit(id: String, amount: BigDecimal): Future[Confirmation] = {
    repo.find(id).ask(ref => Deposit(amount, ref))
  }
}
```