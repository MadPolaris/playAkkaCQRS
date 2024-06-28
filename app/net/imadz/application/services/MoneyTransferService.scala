package net.imadz.application.services

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import akka.util.Timeout
import net.imadz.application.aggregates.MoneyTransferTransactionAggregate._
import net.imadz.application.aggregates.repository.TransactionRepository
import net.imadz.common.CommonTypes.{ApplicationService, Id, iMadzError}
import net.imadz.domain.values.Money

import javax.inject.Inject
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class MoneyTransferService @Inject()(system: ActorSystem[_], transactionRepository: TransactionRepository) extends ApplicationService {
  private implicit val timeout: Timeout = 120.seconds
  private implicit val ec: ExecutionContext = system.executionContext
  private val sharding: ClusterSharding = ClusterSharding(system)

  def transfer(fromUserId: Id, toUserId: Id, amount: Money): Future[Either[iMadzError, Id]] = {
    val transactionId = java.util.UUID.randomUUID()
    for {
      completionResult <- initiateTransaction(fromUserId, toUserId, amount, transactionId)
    } yield completionResult
  }

  private def initiateTransaction(fromUserId: Id, toUserId: Id, amount: Money, transactionId: Id): Future[Either[iMadzError, Id]] = {
    val transactionRef = getTransactionRef(transactionId)
    transactionRef.ask(ref => InitiateMoneyTransferTransaction(fromUserId, toUserId, amount, ref)).map {
      case TransactionResultConfirmation(id, None) => Right(id)
      case TransactionResultConfirmation(_, Some(error)) => Left(error)
    }.map(_.left.map(reason => iMadzError("61000", reason)))
  }


  private def getTransactionRef(transactionId: Id): EntityRef[MoneyTransferTransactionCommand] = {
    transactionRepository.findTransactionById(transactionId)
  }
}