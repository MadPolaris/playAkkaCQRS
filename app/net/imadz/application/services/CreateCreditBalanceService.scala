package net.imadz.application.services

import akka.cluster.sharding.typed.scaladsl.EntityRef
import akka.util.Timeout
import net.imadz.application.aggregates.CreditBalanceAggregate
import net.imadz.application.aggregates.CreditBalanceAggregate.GetBalance
import net.imadz.application.aggregates.factories.CreditBalanceAggregateFactory
import net.imadz.common.CommonTypes.{Id, iMadzError}
import net.imadz.domain.values.Money

import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class CreateCreditBalanceService @Inject()(factory: CreditBalanceAggregateFactory) {
  implicit val askTimeout: Timeout = Timeout(30 seconds)

  def createCreditBalance(userId: Id): Future[Either[iMadzError, List[Money]]] = {
    val entityRef = factory.createCreditBalanceWithoutBalance(userId)
    fetchUserCreditBalance(entityRef)
  }

  def createCreditBalance(userId: Id, initial: Money): Future[Either[iMadzError, List[Money]]] = {
    factory.createCreditBalanceWithDefaultBalance(userId, initial)
      .flatMap(either => either.fold(
        err => Future.successful(Left(err)),
        fetchUserCreditBalance))
  }

  private def fetchUserCreditBalance(entityRef: EntityRef[CreditBalanceAggregate.CreditBalanceCommand]) = {
    entityRef
      .ask(GetBalance.apply)
      .map(confirmation =>
        confirmation.error
          .map(Left.apply[iMadzError, List[Money]])
          .getOrElse(Right(confirmation.balances)))
  }
}
