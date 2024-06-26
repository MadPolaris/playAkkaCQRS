package net.imadz.application.services

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import akka.util.Timeout
import net.imadz.application.aggregates.CreditBalanceAggregate
import net.imadz.application.aggregates.CreditBalanceAggregate._
import net.imadz.application.aggregates.TransactionAggregate._
import net.imadz.application.aggregates.repository.TransactionRepository
import net.imadz.common.CommonTypes.{ApplicationService, Id, iMadzError}
import net.imadz.domain.values.Money

import javax.inject.Inject
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class MoneyTransferService @Inject()(system: ActorSystem[_], transactionRepository: TransactionRepository) extends ApplicationService {
  private implicit val timeout: Timeout = 5.seconds
  private implicit val ec: ExecutionContext = system.executionContext
  private val sharding: ClusterSharding = ClusterSharding(system)

  def transfer(fromUserId: Id, toUserId: Id, amount: Money): Future[Either[iMadzError, Id]] = {
    val transactionId = java.util.UUID.randomUUID()
    for {
      initResult <- initiateTransaction(fromUserId, toUserId, amount, transactionId)
      prepareResult <- initResult.map(id => prepareTransfer(id, fromUserId, amount)).getOrElse(Future.successful(Left(iMadzError("80001", "Failed to initiate transaction"))))
      completionResult <- prepareResult.map(id => completeTransfer(id, fromUserId, toUserId, amount)).getOrElse(Future.successful(Left(iMadzError("80002", "Failed to prepare transfer"))))
    } yield completionResult
  }

  private def initiateTransaction(fromUserId: Id, toUserId: Id, amount: Money, transactionId: Id): Future[Either[iMadzError, Id]] = {
    val transactionRef = getTransactionRef(transactionId)
    transactionRef.ask(ref => InitiateTransaction(fromUserId, toUserId, amount, ref)).map {
      case TransactionInitiationConfirmation(id, None) => Right(id)
      case TransactionInitiationConfirmation(_, Some(error)) => Left(error)
    }
  }

  private def prepareTransfer(transactionId: Id, fromUserId: Id, amount: Money): Future[Either[iMadzError, Id]] = {
    val fromAccount = getAccountRef(fromUserId)
    val transactionRef = getTransactionRef(transactionId)

    fromAccount.ask(ref => ReserveFunds(transactionId, amount, ref)).flatMap {
      case FundsReservationConfirmation(_, None) =>
        transactionRef.ask(ref => PrepareTransaction(transactionId, ref)).map {
          case TransactionPreparationConfirmation(id, None) => Right(id)
          case TransactionPreparationConfirmation(_, Some(error)) => Left(error)
        }
      case FundsReservationConfirmation(_, Some(error)) =>
        Future.successful(Left(error))
    }
  }

  private def completeTransfer(transactionId: Id, fromUserId: Id, toUserId: Id, amount: Money): Future[Either[iMadzError, Id]] = {
    val toAccount = getAccountRef(toUserId)
    val transactionRef = getTransactionRef(transactionId)

    toAccount.ask(ref => Deposit(amount, ref)).flatMap {
      case CreditBalanceConfirmation(None, _) =>
        transactionRef.ask(ref => CompleteTransaction(transactionId, ref)).map {
          case TransactionCompletionConfirmation(id, None) => Right(id)
          case TransactionCompletionConfirmation(_, Some(error)) => Left(error)
        }
      case CreditBalanceConfirmation(Some(error), _) =>
        rollbackTransfer(transactionId, fromUserId, amount).map(_ => Left(error))
    }
  }

  private def rollbackTransfer(transactionId: Id, fromUserId: Id, amount: Money): Future[Unit] = {
    val fromAccount = getAccountRef(fromUserId)
    val transactionRef = getTransactionRef(transactionId)

    for {
      _ <- fromAccount.ask(ref => ReleaseFundsReservation(transactionId, ref))
      _ <- transactionRef.ask(ref => FailTransaction(transactionId, "Transfer failed", ref))
    } yield ()
  }

  private def getAccountRef(userId: Id): EntityRef[CreditBalanceCommand] = {
    sharding.entityRefFor(CreditBalanceAggregate.CreditBalanceEntityTypeKey, userId.toString)
  }

  private def getTransactionRef(transactionId: Id): EntityRef[TransactionCommand] = {
    transactionRepository.findTransactionById(transactionId)
  }
}