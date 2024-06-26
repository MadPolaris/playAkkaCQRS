package net.imadz.application.aggregates.factories

import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import akka.util.Timeout
import net.imadz.application.aggregates.CreditBalanceAggregate.{AddInitial, CreditBalanceCommand, CreditBalanceConfirmation, CreditBalanceEntityTypeKey}
import net.imadz.common.CommonTypes.{Id, iMadzError}
import net.imadz.domain.values.Money

import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

case class CreditBalanceAggregateFactory @Inject()(clusterSharding: ClusterSharding) {
  implicit val askTimeout: Timeout = Timeout(30 seconds)

  def createCreditBalanceWithoutBalance(userId: Id): EntityRef[CreditBalanceCommand] =
    clusterSharding.entityRefFor(CreditBalanceEntityTypeKey, userId.toString)

  def createCreditBalanceWithDefaultBalance(userId: Id, initial: Money): Future[Either[iMadzError, EntityRef[CreditBalanceCommand]]] = {
    val entityRef: EntityRef[CreditBalanceCommand] = clusterSharding
      .entityRefFor[CreditBalanceCommand](CreditBalanceEntityTypeKey, userId.toString)
    for {
      confirmation <- entityRef.ask[CreditBalanceConfirmation](AddInitial(initial, _))
    } yield {
      confirmation.error
        .map(Left.apply[iMadzError, EntityRef[CreditBalanceCommand]])
        .getOrElse(Right(entityRef))
    }
  }

}
