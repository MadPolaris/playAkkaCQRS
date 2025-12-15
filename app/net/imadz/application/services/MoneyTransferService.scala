package net.imadz.application.services

import akka.actor.typed.{ActorSystem, Scheduler}
import akka.cluster.sharding.typed.scaladsl.EntityRef // [新增]
import akka.util.Timeout
import net.imadz.application.services.transactor.MoneyTransferSagaTransactor._
import net.imadz.application.services.transactor.MoneyTransferTransactionRepository
import net.imadz.common.CommonTypes.{ApplicationService, Id}
import net.imadz.domain.values.Money

import javax.inject.Inject
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class MoneyTransferService @Inject()(system: ActorSystem[_], transactionRepository: MoneyTransferTransactionRepository) extends ApplicationService {
  private implicit val timeout: Timeout = 120.seconds
  private implicit val ec: ExecutionContext = system.executionContext
  implicit val scheduler: Scheduler = system.scheduler

  def transfer(fromUserId: Id, toUserId: Id, amount: Money): Future[TransactionResultConfirmation] = {
    val transactionId = java.util.UUID.randomUUID()
    for {
      completionResult <- initiateTransaction(fromUserId, toUserId, amount, transactionId)
    } yield completionResult
  }

  private def initiateTransaction(fromUserId: Id, toUserId: Id, amount: Money, transactionId: Id): Future[TransactionResultConfirmation] = {
    val transactionRef = getTransactionRef(transactionId)
    // EntityRef 的 ask 不需要像 ActorRef 那样 import AskPattern，它自带 ask 方法
    transactionRef.ask(ref => InitiateMoneyTransferTransaction(fromUserId, toUserId, amount, ref))
  }

  // [修改] 返回类型改为 EntityRef
  private def getTransactionRef(transactionId: Id): EntityRef[MoneyTransferTransactionCommand] = {
    transactionRepository.findTransactionById(transactionId)
  }
}