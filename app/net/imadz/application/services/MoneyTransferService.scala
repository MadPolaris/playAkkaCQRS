package net.imadz.application.services

import akka.actor.typed.Scheduler
// [NEW] 引入适配器扩展方法 .toTyped
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.sharding.typed.scaladsl.EntityRef
import akka.util.Timeout
import net.imadz.application.services.transactor.MoneyTransferProtocol._
import net.imadz.application.services.transactor.MoneyTransferTransactionRepository
import net.imadz.common.CommonTypes.{ApplicationService, Id}
import net.imadz.domain.values.Money

import javax.inject.Inject
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

// [CHANGE] 构造函数注入 classicSystem: akka.actor.ActorSystem
class MoneyTransferService @Inject()(classicSystem: akka.actor.ActorSystem, transactionRepository: MoneyTransferTransactionRepository) extends ApplicationService {

  // [NEW] 手动转换为 Typed ActorSystem
  private val system = classicSystem.toTyped

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

  private def getTransactionRef(transactionId: Id): EntityRef[MoneyTransferTransactionCommand] = {
    transactionRepository.findTransactionById(transactionId)
  }
}