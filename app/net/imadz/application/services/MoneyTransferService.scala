package net.imadz.application.services

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.{ActorRef, ActorSystem, Scheduler}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.util.Timeout
import net.imadz.application.aggregates.MoneyTransferTransactionAggregate._
import net.imadz.application.aggregates.repository.MoneyTransferTransactionRepository
import net.imadz.common.CommonTypes.{ApplicationService, Id}
import net.imadz.domain.values.Money

import javax.inject.Inject
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}


class MoneyTransferService @Inject()(system: ActorSystem[_], transactionRepository: MoneyTransferTransactionRepository) extends ApplicationService {
  private implicit val timeout: Timeout = 120.seconds
  private implicit val ec: ExecutionContext = system.executionContext
  private val sharding: ClusterSharding = ClusterSharding(system)
  implicit val scheduler: Scheduler = system.scheduler


  def transfer(fromUserId: Id, toUserId: Id, amount: Money): Future[TransactionResultConfirmation] = {
    val transactionId = java.util.UUID.randomUUID()
    for {
      completionResult <- initiateTransaction(fromUserId, toUserId, amount, transactionId)
    } yield completionResult
  }

  private def initiateTransaction(fromUserId: Id, toUserId: Id, amount: Money, transactionId: Id): Future[TransactionResultConfirmation] = {
    val transactionRef = getTransactionRef(transactionId)
    transactionRef.ask(ref => InitiateMoneyTransferTransaction(fromUserId, toUserId, amount, ref))
  }


  private def getTransactionRef(transactionId: Id): ActorRef[MoneyTransferTransactionCommand] = {
    transactionRepository.findTransactionById(transactionId)
  }
}