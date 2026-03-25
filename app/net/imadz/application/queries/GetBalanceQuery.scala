package net.imadz.application.queries

import akka.util.Timeout
import net.imadz.application.aggregates.CreditBalanceProtocol.GetBalance
import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.common.CommonTypes.Id
import net.imadz.domain.values.Money

import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class GetBalanceQuery @Inject()(creditBalanceRepository: CreditBalanceRepository) {

  implicit val askTimeout: Timeout = Timeout(30 seconds)

  def fetchBalanceByUserId(userId: Id): Future[List[Money]] =
    creditBalanceRepository.findCreditBalanceByUserId(userId)
      .ask(GetBalance)
      .map(confirmation => confirmation.error.map(_ => Nil).getOrElse(confirmation.balances))

}
