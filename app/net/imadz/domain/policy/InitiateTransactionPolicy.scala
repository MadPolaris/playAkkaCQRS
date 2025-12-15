package net.imadz.domain.policy

import net.imadz.application.services.transactor.MoneyTransferTransactionEntity._
import net.imadz.common.CommonTypes.{DomainPolicy, iMadzError}
import net.imadz.domain.values.Money

// 输入：当前状态，(发起人, 接收人, 金额)
// 输出：Either[错误, 事件列表]
object InitiateTransactionPolicy extends DomainPolicy[MoneyTransferTransactionEvent, MoneyTransferTransactionState, (String, String, Money)] {

  private val AlreadyStarted = iMadzError("400", "Transaction already initiated")
  private val InvalidAmount = iMadzError("401", "Amount must be positive")

  override def apply(state: MoneyTransferTransactionState, param: (String, String, Money)): Either[iMadzError, List[MoneyTransferTransactionEvent]] = {
    val (from, to, amount) = param

    // 1. 状态守卫
    if (state.status != Status.New) {
      Left(AlreadyStarted)
    }
    // 2. 业务规则校验
    else if (amount.amount <= 0) {
      Left(InvalidAmount)
    }
    // 3. 决策：生成事件
    else {
      // ID 生成策略：这里我们假设 ID 由外部传入或在 Behavior 中生成，
      // 但为了纯粹性，这里生成的事件通常携带核心业务数据。
      // 注意：TransactionInitiated 中的 ID 是 Saga ID，通常在 Behavior 中确定。
      // 这里我们在 Policy 中生成“意图”，具体 ID 绑定留给 applyEvent 或由 Policy 参数传入 ID 会更合理。
      // 简化起见，我们假设参数里没传 ID，Policy 只决定“发生了这件事”。
      val event = TransactionInitiated(
        from, to, amount,
        System.currentTimeMillis()
      )
      Right(List(event))
    }
  }
}