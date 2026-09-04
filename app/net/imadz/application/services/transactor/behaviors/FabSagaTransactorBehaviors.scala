package net.imadz.application.services.transactor.behaviors

import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.cluster.sharding.typed.scaladsl.EntityRef
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import akka.util.Timeout
import net.imadz.application.services.transactor.{AppSagaContext, FabSagaTransactor}
import net.imadz.application.services.transactor.FabSagaDefinition
import net.imadz.application.services.transactor.FabSagaDefinition.FabSagaArgs
import net.imadz.application.services.transactor.FabSagaProtocol._
import net.imadz.common.Id
import net.imadz.common.CommonTypes.{Id, iMadzError}
import net.imadz.domain.entities.FabSagaTransactionEntity._
import net.imadz.infra.saga.SagaPhase.{CommitPhase, CompensatePhase, PreparePhase}
import net.imadz.infra.saga.SagaTransactionCoordinator.{ManualFixStep, ResolveSuspended, StartSaga, TracingStep, TransactionResult}
import net.imadz.infra.saga.SagaTransactionCoordinator

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

/** FabSagaTransaction status aggregate. Since the v3 engine migration it no longer
  * orchestrates steps itself — it journals the business intent, hands (txId, args) to
  * the shared v3 coordinator via `StartSaga`, and records the terminal result.
  * Step expansion lives in [[FabSagaDefinition]]; participants are rebuilt by the engine. */
object FabSagaTransactorBehaviors {

  def apply(
    id: String,
    coordinator: EntityRef[SagaTransactionCoordinator.Command],
    fabContext: AppSagaContext
  ): Behavior[FabSagaCommand] = {

    akka.actor.typed.scaladsl.Behaviors.setup { context =>
      implicit val ec: ExecutionContext = context.executionContext

      EventSourcedBehavior[FabSagaCommand, FabSagaTransactionEvent, FabSagaTransactionState](
        persistenceId = PersistenceId("FabSagaTransaction", id),
        emptyState = FabSagaTransactionState(id = Some(id)),

        commandHandler = (state, command) => command match {
          case cmd: InitiateWaferTransfer =>
            initiateHandler(state, cmd.sourceLotId, cmd.targetLotId, cmd.waferIds, cmd.waferNames, cmd.replyTo, coordinator, context, id)
          case cmd: InitiateLotSplit =>
            initiateHandler(state, cmd.sourceLotId, cmd.targetLotId, cmd.waferIds, cmd.waferNames, cmd.replyTo, coordinator, context, id)
          case cmd: InitiateLotMerge =>
            val firstSource = cmd.sourceLotIds.headOption.getOrElse(Id.gen)
            initiateHandler(state, firstSource, cmd.targetLotId, cmd.waferIds, cmd.waferNames, cmd.replyTo, coordinator, context, id)
          case cmd: UpdateFabSagaStatus =>
            updateStatusHandler(state, cmd)
          case cmd: AdminManualFixStep =>
            adminManualFixHandler(state, cmd, coordinator)
          case cmd: AdminResumeTransaction =>
            adminResumeHandler(state, cmd, coordinator, context)
        },

        eventHandler = (state, event) => state.applyEvent(event)
      )
        .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 3))
        .withTagger(_ => Set(FabSagaTransactor.tags(math.abs(id.hashCode % FabSagaTransactor.tags.size)), "fab-view"))
        .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1))
    }
  }

  // --- Initiate (transfer / split / merge share one plan: reserve -> commit both lots) ---
  private def initiateHandler(
    state: FabSagaTransactionState,
    sourceLotId: Id,
    targetLotId: Id,
    waferIds: Set[Id],
    waferNames: Set[String],
    replyTo: ActorRef[FabSagaConfirmation],
    coordinator: EntityRef[SagaTransactionCoordinator.Command],
    context: akka.actor.typed.scaladsl.ActorContext[FabSagaCommand],
    id: String
  )(implicit ec: ExecutionContext): Effect[FabSagaTransactionEvent, FabSagaTransactionState] = {

    val event = TransactionInitiated(sourceLotId, targetLotId, waferIds, System.currentTimeMillis())
    Effect.persist(event).thenRun { _ =>
      val transactionId = state.id.getOrElse(id)
      implicit val timeout: Timeout = 30.seconds

      val args = FabSagaArgs(
        sourceLotId = sourceLotId.toString,
        targetLotId = targetLotId.toString,
        waferIds = waferIds.map(_.toString).toList,
        waferNames = waferNames.toList)
      val argsBytes = FabSagaDefinition.codec.encode(args)

      context.ask(coordinator, (ref: ActorRef[TransactionResult]) =>
        StartSaga(
          transactionId = transactionId,
          definitionName = FabSagaDefinition.Name,
          definitionVersion = FabSagaDefinition.Version,
          argsBytes = argsBytes,
          traceId = s"TRACE-${transactionId.take(8)}",
          completionReply = Some(ref))
      ) {
        case scala.util.Success(result) =>
          UpdateFabSagaStatus(Id.of(transactionId), result, replyTo)
        case scala.util.Failure(ex) =>
          val failed = startFailedResult(transactionId, ex)
          UpdateFabSagaStatus(Id.of(transactionId), failed, replyTo)
      }
    }
  }

  private def startFailedResult(transactionId: String, ex: Throwable): TransactionResult = {
    val snapshot = SagaTransactionCoordinator.StatusSnapshot(
      transactionId = transactionId,
      definitionName = FabSagaDefinition.Name,
      definitionVersion = FabSagaDefinition.Version,
      traceId = "",
      status = SagaTransactionCoordinator.Failed.toString,
      currentPhase = PreparePhase.toString,
      currentStepGroup = 0,
      isPaused = false,
      singleStep = false,
      failReason = Some(ex.getMessage),
      steps = Nil)
    TransactionResult(successful = false, snapshot, ex.getMessage)
  }

  // --- Update Status ---
  private def updateStatusHandler(
    state: FabSagaTransactionState,
    cmd: UpdateFabSagaStatus
  ): Effect[FabSagaTransactionEvent, FabSagaTransactionState] = {

    val event = if (cmd.newStatus.successful) {
      TransactionCompleted(cmd.id.toString, System.currentTimeMillis())
    } else {
      val reason = Option(cmd.newStatus.failReason).getOrElse("Unknown Error")
      TransactionFailed(cmd.id.toString, reason, System.currentTimeMillis())
    }

    Effect.persist(event).thenReply(cmd.replyTo) { _ =>
      FabSagaConfirmation(
        cmd.id,
        if (cmd.newStatus.successful) None else Some(cmd.newStatus.failReason),
        cmd.newStatus.snapshot.steps.zipWithIndex.map { case (step, idx) =>
          TracingStep(idx + 1, step.stepId, "SagaTransactionStep",
            step.phase, step.participantName,
            step.status, step.retries, step.maxRetries, step.timeoutInMillis,
            step.retryWhenRecoveredOngoing, false, step.error)
        }
      )
    }
  }

  // --- Admin ---
  private def adminManualFixHandler(
    state: FabSagaTransactionState,
    cmd: AdminManualFixStep,
    coordinator: EntityRef[SagaTransactionCoordinator.Command]
  ): Effect[FabSagaTransactionEvent, FabSagaTransactionState] = {
    val phase = cmd.phase.toLowerCase match {
      case "prepare" => PreparePhase
      case "commit" => CommitPhase
      case "compensate" => CompensatePhase
      case _ => PreparePhase
    }
    coordinator ! ManualFixStep(cmd.stepId, phase, None)
    Effect.reply(cmd.replyTo)(FabSagaConfirmation(Id.of(state.id.getOrElse("")), None, Nil))
  }

  private def adminResumeHandler(
    state: FabSagaTransactionState,
    cmd: AdminResumeTransaction,
    coordinator: EntityRef[SagaTransactionCoordinator.Command],
    context: akka.actor.typed.scaladsl.ActorContext[FabSagaCommand]
  )(implicit ec: ExecutionContext): Effect[FabSagaTransactionEvent, FabSagaTransactionState] = {
    implicit val timeout: Timeout = 30.seconds
    val transactionId = state.id.getOrElse("")

    context.ask(coordinator, (ref: ActorRef[TransactionResult]) =>
      ResolveSuspended(Some(ref))
    ) {
      case scala.util.Success(result) =>
        UpdateFabSagaStatus(Id.of(transactionId), result, cmd.replyTo)
      case scala.util.Failure(_) =>
        val failed = startFailedResult(transactionId, new IllegalStateException("resume failed"))
        UpdateFabSagaStatus(Id.of(transactionId), failed, cmd.replyTo)
    }
    Effect.none
  }
}
