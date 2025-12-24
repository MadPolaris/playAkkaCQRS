package net.imadz.banking.application.aggregates.behaviors

import net.imadz.application.aggregates.CreditBalanceAggregate._
import net.imadz.application.aggregates.behaviors.CreditBalanceCommandHelpers
import net.imadz.common.CommonTypes.iMadzError
import net.imadz.common.test.CommandHelperTestKit
import net.imadz.domain.entities.CreditBalanceEntity._
import net.imadz.domain.values.Money

import java.util.{Currency, UUID}

class CreditBalanceCommandHelpersSpec extends CommandHelperTestKit with CreditBalanceCommandHelpers {
  val usd = Currency.getInstance("USD")
  // 准备公共数据
  val id = UUID.randomUUID()
  val money100 = Money(100, usd)
  val emptyState = CreditBalanceState(id, Map.empty, Map.empty, Map.empty)
  // 假设 replyTo 是 null 或 mock，因为 Helper 是纯函数，通常只透传不使用，或者 CommandHelper 接口里根本没用到 replyTo
  // 注意：CommandHelper 接口里确实没用到 replyTo，这太棒了，完全解耦！

  // Test Case 1: Deposit
  verifyHelper("DepositHelper", DepositHelper)(
    state = emptyState,
    cmd = Deposit(money100, null),
    expectedParam = money100,
    expectedSuccess = CreditBalanceConfirmation(None, Nil),
    expectedError = CreditBalanceConfirmation(Some(iMadzError("TEST_ERR", "Test error message")), Nil)
  )

  // Test Case 2: Withdraw
  verifyHelper("WithdrawHelper", WithdrawHelper)(
    state = emptyState,
    cmd = Withdraw(money100, null),
    expectedParam = money100,
    expectedSuccess = CreditBalanceConfirmation(None, Nil),
    expectedError = CreditBalanceConfirmation(Some(iMadzError("TEST_ERR", "Test error message")), Nil)
  )

  // Test Case 3: ReserveFunds (Tuple Param)
  val transferId = UUID.randomUUID()
  verifyHelper("ReserveFundsHelper", ReserveFundsHelper)(
    state = emptyState,
    cmd = ReserveFunds(transferId, money100, null),
    expectedParam = (transferId, money100),
    expectedSuccess = FundsReservationConfirmation(transferId, None),
    expectedError = FundsReservationConfirmation(transferId, Some(iMadzError("TEST_ERR", "Test error message")))
  )

  // ... 其他命令类似
}