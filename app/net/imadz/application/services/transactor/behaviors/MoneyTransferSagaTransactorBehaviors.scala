package net.imadz.application.services.transactor.behaviors

import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.cluster.sharding.typed.scaladsl.EntityRef
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import akka.util.Timeout
import net.imadz.application.services.transactor.MoneyTransferSagaTransactor._
import net.imadz.application.services.transactor.{FromAccountParticipant, MoneyTransferContext, ToAccountParticipant}
import net.imadz.common.CommonTypes.{Id, iMadzError}
import net.imadz.common.Id
import net.imadz.domain.entities.MoneyTransferTransactionEntity._
import net.imadz.domain.policy.InitiateTransactionPolicy
import net.imadz.domain.values.Money
import net.imadz.infra.saga.SagaPhase.{CommitPhase, CompensatePhase, PreparePhase}
import net.imadz.infra.saga.SagaTransactionCoordinator.{StartTransaction, TransactionResult}
import net.imadz.infra.saga.SagaTransactionStep
import net.imadz.infrastructure.persistence.{TransactionEventAdapter, TransactionSnapshotAdapter}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

object MoneyTransferSagaTransactorBehaviors {

  def apply(
             id: String,
             coordinator: EntityRef[net.imadz.infra.saga.SagaTransactionCoordinator.Command],
             moneyTransferContext: MoneyTransferContext
           ): Behavior[MoneyTransferTransactionCommand] = {

    akka.actor.typed.scaladsl.Behaviors.setup { context =>
      implicit val ec: ExecutionContext = context.executionContext

      EventSourcedBehavior[MoneyTransferTransactionCommand, MoneyTransferTransactionEvent, MoneyTransferTransactionState](
        persistenceId = PersistenceId("MoneyTransferTransaction", id),
        emptyState = MoneyTransferTransactionState(id = Some(id)),

        commandHandler = (state, command) => command match {
          case cmd: InitiateMoneyTransferTransaction =>
            initiateHandler(state, cmd, coordinator, moneyTransferContext, context, id)

          case cmd: UpdateMoneyTransferTransactionStatus =>
            updateStatusHandler(state, cmd)
        },

        eventHandler = (state, event) => state.applyEvent(event)
      )
        .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 3))
        .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1))
        .eventAdapter(new TransactionEventAdapter)
        .snapshotAdapter(new TransactionSnapshotAdapter)
    }
  }

  // --- Handlers ---

  private def initiateHandler(
                               state: MoneyTransferTransactionState,
                               cmd: InitiateMoneyTransferTransaction,
                               coordinator: EntityRef[net.imadz.infra.saga.SagaTransactionCoordinator.Command],
                               moneyTransferContext: MoneyTransferContext,
                               context: akka.actor.typed.scaladsl.ActorContext[MoneyTransferTransactionCommand],
                               id: String
                             )(implicit ec: ExecutionContext): Effect[MoneyTransferTransactionEvent, MoneyTransferTransactionState] = {

    // 1. 调用 Policy 进行纯逻辑判断
    val decision = InitiateTransactionPolicy(state, (cmd.fromUserId.toString, cmd.toUserId.toString, cmd.amount))

    decision match {
      case Left(error) =>
        // 校验失败，直接回复，不持久化
        Effect.reply(cmd.replyTo)(TransactionResultConfirmation(cmd.fromUserId, Some(error.message), Nil))

      case Right(events) =>
        // 校验成功，持久化 Initiated 事件
        Effect.persist(events).thenRun { _ =>
          // 2. [副作用] 自动生成 Steps 并调用 Coordinator
          val steps = createSteps(cmd.fromUserId, cmd.toUserId, cmd.amount)
          val transactionId = state.id.getOrElse(id) // 使用 PersistenceId 作为 TxId

          implicit val timeout: Timeout = 30.seconds

          context.ask(coordinator, (ref: ActorRef[TransactionResult]) =>
            StartTransaction[iMadzError, String, MoneyTransferContext](transactionId, steps, Some(ref))
          ) {
            case scala.util.Success(result) =>
              UpdateMoneyTransferTransactionStatus(Id.of(transactionId), result, cmd.replyTo)
            case scala.util.Failure(ex) =>
              // Ask 失败处理
              val failedResult = TransactionResult(successful = false, null, Nil)
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
      val reason = if (cmd.newStatus.failReason != null) cmd.newStatus.failReason else "Unknown Error"
      TransactionFailed(cmd.id.toString, reason, System.currentTimeMillis())
    }

    Effect.persist(event).thenReply(cmd.replyTo) { _ =>
      TransactionResultConfirmation(
        cmd.id,
        if (cmd.newStatus.successful) None else Some(cmd.newStatus.failReason),
        cmd.newStatus.tracingSteps
      )
    }
  }

  // --- Automatic Steps Generation (TCC Pattern) ---
  private def createSteps(from: Id, to: Id, amount: Money)(implicit ec: ExecutionContext): List[SagaTransactionStep[iMadzError, String, MoneyTransferContext]] = {

    // 1. 实例化参与者
    val fromPart = new FromAccountParticipant(from, amount)
    val toPart = new ToAccountParticipant(to, amount)

    // 2. 编排 TCC 步骤
    List(
      // Step 1: Prepare (Reserve)
      SagaTransactionStep[iMadzError, String, MoneyTransferContext]("reserve-from", PreparePhase, fromPart, maxRetries = 5),
      SagaTransactionStep[iMadzError, String, MoneyTransferContext]("record-to", PreparePhase, toPart, maxRetries = 5),

      // Step 2: Commit
      SagaTransactionStep[iMadzError, String, MoneyTransferContext]("commit-from", CommitPhase, fromPart, maxRetries = 5),
      SagaTransactionStep[iMadzError, String, MoneyTransferContext]("commit-to", CommitPhase, toPart, maxRetries = 5),

      // Step 3: Compensate
      SagaTransactionStep[iMadzError, String, MoneyTransferContext]("compensate-from", CompensatePhase, fromPart, maxRetries = 5),
      SagaTransactionStep[iMadzError, String, MoneyTransferContext]("compensate-to", CompensatePhase, toPart, maxRetries = 5)
    )
  }
}