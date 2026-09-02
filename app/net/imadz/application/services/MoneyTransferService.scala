package net.imadz.application.services

import akka.actor.typed.Scheduler
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import net.imadz.application.services.transactor.MoneyTransferSagaDefinition
import net.imadz.common.CommonTypes.{ApplicationService, Id, iMadzError}
import net.imadz.common.Id
import net.imadz.domain.values.Money
import net.imadz.infra.saga.SagaTransactionCoordinator.{StatusSnapshot, TransactionResult}
import net.imadz.infra.saga.dsl.{SagaRunner, SagaStartRejectedException}
import net.imadz.infrastructure.bootstrap.SagaEngineBootstrap

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/** Submission handle: the transaction id (idempotency key) plus the terminal-result
  * future the caller may inspect or ignore (durable callers poll statusOf instead). */
final case class TransferSubmission(transactionId: Id, result: Future[TransactionResult])

object MoneyTransferService {
  /** Window in which an immediate start rejection (preCheck / unknown definition / ask
    * failure) is surfaced synchronously; anything longer is treated as accepted. */
  private val StartAckWindow: FiniteDuration = 2.seconds
}

class MoneyTransferService @Inject()(classicSystem: akka.actor.ActorSystem,
                                      sharding: ClusterSharding) extends ApplicationService with SagaEngineBootstrap {

  private val system = classicSystem.toTyped
  private implicit val ec: ExecutionContext = system.executionContext
  private implicit val scheduler: Scheduler = system.scheduler

  private val runner: SagaRunner[iMadzError, MoneyTransferSagaDefinition.TransferArgs] =
    MoneyTransferSagaDefinition.runner(system, txId => coordinatorRef(sharding, txId))

  /** Starts (or resumes — idempotent per txId) a money transfer. Returns as soon as the
    * start is accepted; the terminal result arrives via `result` or by polling statusOf. */
  def transfer(fromUserId: Id, toUserId: Id, amount: Money): Future[TransferSubmission] = {
    val transactionId = Id.gen
    val terminal = runner.run(
      transactionId.toString,
      MoneyTransferSagaDefinition.TransferArgs.of(fromUserId.toString, toUserId.toString, amount))
    val submission = TransferSubmission(transactionId, terminal)
    Future.firstCompletedOf(Seq(
      terminal.transformWith {
        case Success(result) => Future.successful(submission.copy(result = Future.successful(result))) // fast completion
        case Failure(ex: SagaStartRejectedException) => Future.failed(ex)                              // fast rejection
        case Failure(_) => Future.successful(submission)                                               // mid-flight failure — poll statusOf
      },
      akka.pattern.after(MoneyTransferService.StartAckWindow, system.classicSystem.scheduler)(Future.successful(submission))
    ))
  }

  /** Durable status polling — survives entity restarts and node crashes. */
  def statusOf(transactionId: String): Future[Option[StatusSnapshot]] =
    runner.statusOf(transactionId)
}
