package net.imadz.application.services.transactor

import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.cluster.sharding.typed.scaladsl.{EntityRef, EntityTypeKey}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import akka.util.Timeout
import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.common.CommonTypes.{Id, iMadzError}
import net.imadz.common._
import net.imadz.domain.values.Money
import net.imadz.infra.saga.SagaPhase.{CommitPhase, CompensatePhase, PreparePhase}
import net.imadz.infra.saga.SagaTransactionCoordinator.{StartTransaction, TracingStep, TransactionResult}
import net.imadz.infra.saga.{SagaTransactionCoordinator, SagaTransactionStep}
import net.imadz.infrastructure.persistence.{TransactionEventAdapter, TransactionSnapshotAdapter}
import play.api.libs.json.{Json, OWrites}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

object MoneyTransferSagaTransactor {
  val entityTypeKey: EntityTypeKey[MoneyTransferTransactionCommand] = EntityTypeKey[MoneyTransferTransactionCommand]("MoneyTransferTransaction")

  // --- Commands ---
  sealed trait MoneyTransferTransactionCommand extends CborSerializable

  case class InitiateMoneyTransferTransaction(fromUserId: Id, toUserId: Id, amount: Money, replyTo: ActorRef[TransactionResultConfirmation]) extends MoneyTransferTransactionCommand

  case class UpdateMoneyTransferTransactionStatus(id: Id, newStatus: TransactionResult, replyTo: ActorRef[TransactionResultConfirmation]) extends MoneyTransferTransactionCommand

  // --- Command Replies ---
  case class TransactionResultConfirmation(transactionId: Id, error: Option[String], tracing: List[TracingStep]) extends CborSerializable

  object TransactionResultConfirmation {
    implicit val confirmationWrites: OWrites[TransactionResultConfirmation] = Json.writes[TransactionResultConfirmation]
  }

  // --- Events (Domain Events) ---
  sealed trait MoneyTransferTransactionEvent extends CborSerializable

  case class TransactionInitiated(fromUserId: String, toUserId: String, amount: Money, timestamp: Long) extends MoneyTransferTransactionEvent

  case class TransactionPrepared(id: String, timestamp: Long) extends MoneyTransferTransactionEvent

  case class TransactionCompleted(id: String, timestamp: Long) extends MoneyTransferTransactionEvent

  case class TransactionFailed(id: String, reason: String, timestamp: Long) extends MoneyTransferTransactionEvent

  // --- State ---
  sealed trait Status extends CborSerializable

  object Status {
    case object New extends Status

    case object Initiated extends Status

    case object Prepared extends Status

    case object Completed extends Status

    case class Failed(reason: String) extends Status
  }

  case class MoneyTransferTransactionState(
                                            id: Option[String] = None,
                                            fromUserId: Option[String] = None,
                                            toUserId: Option[String] = None,
                                            amount: Option[Money] = None,
                                            status: Status = Status.New
                                          ) extends CborSerializable {
    def applyEvent(event: MoneyTransferTransactionEvent): MoneyTransferTransactionState = event match {
      case TransactionInitiated(from, to, amt, _) =>
        copy(id = None, fromUserId = Some(from), toUserId = Some(to), amount = Some(amt), status = Status.Initiated)
      case TransactionPrepared(txId, _) =>
        copy(id = Some(txId), status = Status.Prepared)
      case TransactionCompleted(_, _) =>
        copy(status = Status.Completed)
      case TransactionFailed(_, reason, _) =>
        copy(status = Status.Failed(reason))
    }
  }

  // --- Transaction Steps Factory ---
  def createTransactionSteps(fromUserId: Id, toUserId: Id, amount: Money, repository: CreditBalanceRepository)(implicit ec: ExecutionContext): List[SagaTransactionStep[iMadzError, String]] = {
    val fromAccountParticipant = new FromAccountParticipant(fromUserId, amount, repository)
    val toAccountParticipant = new ToAccountParticipant(toUserId, amount, repository)

    List(
      SagaTransactionStep("reserve-amount-from-account", PreparePhase, fromAccountParticipant, 5),
      SagaTransactionStep("record-incoming-amount-to-account", PreparePhase, toAccountParticipant, 5),
      SagaTransactionStep("commit-from-account", CommitPhase, fromAccountParticipant, 5),
      SagaTransactionStep("commit-to-account", CommitPhase, toAccountParticipant, 5),
      SagaTransactionStep("compensate-from-account", CompensatePhase, fromAccountParticipant, 5),
      SagaTransactionStep("compensate-to-account", CompensatePhase, toAccountParticipant, 5)
    )
  }

  // --- Actor Behavior ---
  def apply(id: String, coordinator: EntityRef[SagaTransactionCoordinator.Command], repository: CreditBalanceRepository)
           (implicit ec: ExecutionContext): Behavior[MoneyTransferTransactionCommand] = {
    akka.actor.typed.scaladsl.Behaviors.setup { context =>
      EventSourcedBehavior[MoneyTransferTransactionCommand, MoneyTransferTransactionEvent, MoneyTransferTransactionState](
        persistenceId = PersistenceId("MoneyTransferTransaction", id),
        emptyState = MoneyTransferTransactionState(id = Some(id)),
        commandHandler = (state, command) => {
          command match {
            case cmd: InitiateMoneyTransferTransaction =>
              initiateHandler(state, cmd, coordinator, repository, context)
            case cmd: UpdateMoneyTransferTransactionStatus =>
              updateStatusHandler(state, cmd)
          }
        },
        eventHandler = (state, event) => state.applyEvent(event)
      )
        .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 3))
        .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1))
        .eventAdapter(new TransactionEventAdapter)
        .snapshotAdapter(new TransactionSnapshotAdapter)
    }
  }

  private def initiateHandler(
                               state: MoneyTransferTransactionState,
                               cmd: InitiateMoneyTransferTransaction,
                               coordinator: EntityRef[SagaTransactionCoordinator.Command],
                               repository: CreditBalanceRepository,
                               context: akka.actor.typed.scaladsl.ActorContext[MoneyTransferTransactionCommand]
                             )(implicit ec: ExecutionContext): Effect[MoneyTransferTransactionEvent, MoneyTransferTransactionState] = {
    if (state.status != Status.New) {
      // 幂等处理：如果已经在进行中，可以选择什么都不做或者回复当前状态
      // 这里简化为忽略
      Effect.none
    } else {
      val event = TransactionInitiated(cmd.fromUserId.toString, cmd.toUserId.toString, cmd.amount, System.currentTimeMillis())

      Effect.persist(event).thenRun { _ =>
        val steps = createTransactionSteps(cmd.fromUserId, cmd.toUserId, cmd.amount, repository)
        val transactionId = state.id.getOrElse(Id.gen.toString) // Should rely on the ID passed to factory

        // 使用 Ask 模式与 Coordinator 交互，将结果转换回 Transactor 的 Command
        implicit val timeout: Timeout = 30.seconds
        context.ask(coordinator, (ref: ActorRef[TransactionResult]) => StartTransaction[iMadzError, String](transactionId, steps, Some(ref))) {
          case scala.util.Success(result) =>
            UpdateMoneyTransferTransactionStatus(Id.of(transactionId), result, cmd.replyTo)
          case scala.util.Failure(ex) =>
            // 处理 Ask 失败，构造一个失败的 Result
            val failedResult = TransactionResult(successful = false, null, Nil) // 这里简化处理
            UpdateMoneyTransferTransactionStatus(Id.of(transactionId), failedResult, cmd.replyTo)
        }
      }
    }
  }

  private def updateStatusHandler(
                                   state: MoneyTransferTransactionState,
                                   cmd: UpdateMoneyTransferTransactionStatus
                                 ): Effect[MoneyTransferTransactionEvent, MoneyTransferTransactionState] = {
    val event = if (cmd.newStatus.successful) {
      TransactionCompleted(cmd.id.toString, System.currentTimeMillis())
    } else {
      TransactionFailed(cmd.id.toString, cmd.newStatus.failReason, System.currentTimeMillis())
    }

    Effect.persist(event).thenReply(cmd.replyTo) { _ =>
      TransactionResultConfirmation(cmd.id, if (cmd.newStatus.successful) None else Some(cmd.newStatus.failReason), cmd.newStatus.tracingSteps)
    }
  }
}