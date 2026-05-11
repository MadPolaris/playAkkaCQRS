package net.imadz.application.services.transactor.behaviors

import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.cluster.sharding.typed.scaladsl.EntityRef
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import akka.util.Timeout
import net.imadz.application.services.transactor.FabSagaProtocol._
import net.imadz.application.services.transactor.{FabTransactionContext, SourceLotParticipant, TargetLotParticipant, WaferTransferParticipant}
import net.imadz.common.CommonTypes.{Id, iMadzError}
import net.imadz.common.Id
import net.imadz.domain.entities.FabSagaTransactionEntity._
import net.imadz.infra.saga.SagaPhase.{CommitPhase, CompensatePhase, PreparePhase}
import net.imadz.infra.saga.SagaTransactionCoordinator.{ManualFixStep, ResolveSuspended, StartTransaction, TracingStep, TransactionResult}
import net.imadz.infra.saga.{SagaTransactionCoordinator, SagaTransactionStep}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

object FabSagaTransactorBehaviors {

  def apply(
    id: String,
    coordinator: EntityRef[SagaTransactionCoordinator.Command],
    fabContext: FabTransactionContext
  ): Behavior[FabSagaCommand] = {

    akka.actor.typed.scaladsl.Behaviors.setup { context =>
      implicit val ec: ExecutionContext = context.executionContext

      EventSourcedBehavior[FabSagaCommand, FabSagaTransactionEvent, FabSagaTransactionState](
        persistenceId = PersistenceId("FabSagaTransaction", id),
        emptyState = FabSagaTransactionState(id = Some(id)),

        commandHandler = (state, command) => command match {
          case cmd: InitiateWaferTransfer =>
            initiateTransferHandler(state, cmd, coordinator, fabContext, context, id)
          case cmd: InitiateLotSplit =>
            initiateSplitHandler(state, cmd, coordinator, fabContext, context, id)
          case cmd: InitiateLotMerge =>
            initiateMergeHandler(state, cmd, coordinator, fabContext, context, id)
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
        .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1))
    }
  }

  // --- Initiate Wafer Transfer ---
  private def initiateTransferHandler(
    state: FabSagaTransactionState,
    cmd: InitiateWaferTransfer,
    coordinator: EntityRef[SagaTransactionCoordinator.Command],
    fabContext: FabTransactionContext,
    context: akka.actor.typed.scaladsl.ActorContext[FabSagaCommand],
    id: String
  )(implicit ec: ExecutionContext): Effect[FabSagaTransactionEvent, FabSagaTransactionState] = {

    val event = TransactionInitiated(cmd.sourceLotId, cmd.targetLotId, cmd.waferIds, System.currentTimeMillis())
    Effect.persist(event).thenRun { _ =>
      val steps = createTransferSteps(cmd.sourceLotId, cmd.targetLotId, cmd.waferIds)
      val transactionId = state.id.getOrElse(id)
      implicit val timeout: Timeout = 30.seconds

      context.ask(coordinator, (ref: ActorRef[TransactionResult]) =>
        StartTransaction[iMadzError, String, FabTransactionContext](transactionId, steps, Some(ref))
      ) {
        case scala.util.Success(result) =>
          UpdateFabSagaStatus(Id.of(transactionId), result, cmd.replyTo)
        case scala.util.Failure(_) =>
          val failed = TransactionResult(successful = false, SagaTransactionCoordinator.State(traceId = ""))
          UpdateFabSagaStatus(Id.of(transactionId), failed, cmd.replyTo)
      }
    }
  }

  // Initiate Lot Split uses same transfer steps (source -> target -> wafers)
  private def initiateSplitHandler(
    state: FabSagaTransactionState,
    cmd: InitiateLotSplit,
    coordinator: EntityRef[SagaTransactionCoordinator.Command],
    fabContext: FabTransactionContext,
    context: akka.actor.typed.scaladsl.ActorContext[FabSagaCommand],
    id: String
  )(implicit ec: ExecutionContext): Effect[FabSagaTransactionEvent, FabSagaTransactionState] = {

    val event = TransactionInitiated(cmd.sourceLotId, cmd.targetLotId, cmd.waferIds, System.currentTimeMillis())
    Effect.persist(event).thenRun { _ =>
      val steps = createTransferSteps(cmd.sourceLotId, cmd.targetLotId, cmd.waferIds)
      val transactionId = state.id.getOrElse(id)
      implicit val timeout: Timeout = 30.seconds

      context.ask(coordinator, (ref: ActorRef[TransactionResult]) =>
        StartTransaction[iMadzError, String, FabTransactionContext](transactionId, steps, Some(ref))
      ) {
        case scala.util.Success(result) =>
          UpdateFabSagaStatus(Id.of(transactionId), result, cmd.replyTo)
        case scala.util.Failure(_) =>
          val failed = TransactionResult(successful = false, SagaTransactionCoordinator.State(traceId = ""))
          UpdateFabSagaStatus(Id.of(transactionId), failed, cmd.replyTo)
      }
    }
  }

  // Initiate Lot Merge
  private def initiateMergeHandler(
    state: FabSagaTransactionState,
    cmd: InitiateLotMerge,
    coordinator: EntityRef[SagaTransactionCoordinator.Command],
    fabContext: FabTransactionContext,
    context: akka.actor.typed.scaladsl.ActorContext[FabSagaCommand],
    id: String
  )(implicit ec: ExecutionContext): Effect[FabSagaTransactionEvent, FabSagaTransactionState] = {

    val firstSource = cmd.sourceLotIds.headOption.getOrElse(Id.gen)
    val event = TransactionInitiated(firstSource, cmd.targetLotId, cmd.waferIds, System.currentTimeMillis())
    Effect.persist(event).thenRun { _ =>
      val transactionId = state.id.getOrElse(id)
      implicit val timeout: Timeout = 30.seconds

      context.ask(coordinator, (ref: ActorRef[TransactionResult]) =>
        StartTransaction[iMadzError, String, FabTransactionContext](transactionId, List.empty, Some(ref))
      ) {
        case scala.util.Success(result) =>
          UpdateFabSagaStatus(Id.of(transactionId), result, cmd.replyTo)
        case scala.util.Failure(_) =>
          val failed = TransactionResult(successful = false, SagaTransactionCoordinator.State(traceId = ""))
          UpdateFabSagaStatus(Id.of(transactionId), failed, cmd.replyTo)
      }
    }
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
        cmd.newStatus.state.steps.zipWithIndex.map { case (step, idx) =>
          TracingStep(idx + 1, step.stepId, step.getClass.getSimpleName,
            step.phase.toString, step.participant.getClass.getSimpleName,
            "Unknown", 0, step.maxRetries, step.timeoutDuration.toMillis,
            step.retryWhenRecoveredOngoing, false, None)
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
        val failed = TransactionResult(successful = false, SagaTransactionCoordinator.State(traceId = ""))
        UpdateFabSagaStatus(Id.of(transactionId), failed, cmd.replyTo)
    }
    Effect.none
  }

  // --- Step Generation (TCC Pattern) ---
  def createTransferSteps(
    sourceLotId: Id, targetLotId: Id, waferIds: Set[Id]
  )(implicit ec: ExecutionContext): List[SagaTransactionStep[iMadzError, String, FabTransactionContext]] = {

    val sourcePart = SourceLotParticipant(sourceLotId, waferIds)
    val targetPart = TargetLotParticipant(targetLotId, waferIds)

    // Prepare phase: source reserve (group 1) -> target reserve (group 1) -> wafer reserves (group 2, parallel)
    val prepareSteps = List(
      SagaTransactionStep("reserve-source-lot", PreparePhase, sourcePart, maxRetries = 5, stepGroup = 1),
      SagaTransactionStep("reserve-target-lot", PreparePhase, targetPart, maxRetries = 5, stepGroup = 1)
    ) ++ waferIds.zipWithIndex.map { case (wid, i) =>
      val waferPart = WaferTransferParticipant(wid, targetLotId)
      SagaTransactionStep(s"reserve-wafer-$i", PreparePhase, waferPart, maxRetries = 5, stepGroup = 2)
    }

    // Commit phase: wafer commits (group 2, parallel) -> source commit -> target commit
    val commitSteps = waferIds.zipWithIndex.map { case (wid, i) =>
      val waferPart = WaferTransferParticipant(wid, targetLotId)
      SagaTransactionStep(s"commit-wafer-$i", CommitPhase, waferPart, maxRetries = 5, stepGroup = 2)
    } ++ List(
      SagaTransactionStep("commit-source-lot", CommitPhase, sourcePart, maxRetries = 5, stepGroup = 1),
      SagaTransactionStep("commit-target-lot", CommitPhase, targetPart, maxRetries = 5, stepGroup = 1)
    )

    // Compensate phase: reverse order — target cancel -> source release -> wafer releases (parallel)
    val compensateSteps = List(
      SagaTransactionStep("cancel-target-lot", CompensatePhase, targetPart, maxRetries = 5, stepGroup = 1),
      SagaTransactionStep("release-source-lot", CompensatePhase, sourcePart, maxRetries = 5, stepGroup = 1)
    ) ++ waferIds.zipWithIndex.map { case (wid, i) =>
      val waferPart = WaferTransferParticipant(wid, targetLotId)
      SagaTransactionStep(s"release-wafer-$i", CompensatePhase, waferPart, maxRetries = 5, stepGroup = 2)
    }

    prepareSteps ++ commitSteps ++ compensateSteps
  }
}
