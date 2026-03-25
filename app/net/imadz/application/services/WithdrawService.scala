package net.imadz.application.services

import akka.util.Timeout
import net.imadz.application.aggregates.CreditBalanceProtocol.{CreditBalanceConfirmation, Withdraw}
import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.common.CommonTypes.Id
import net.imadz.domain.values.Money

import javax.inject.Inject
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class WithdrawService @Inject()(creditBalanceRepository: CreditBalanceRepository) {
  implicit val askTimeout: Timeout = Timeout(30 seconds)

  def requestWithdraw(userId: Id, amount: Money): Future[CreditBalanceConfirmation] =
    creditBalanceRepository.findCreditBalanceByUserId(userId)
      .ask(Withdraw(amount, _))
}
