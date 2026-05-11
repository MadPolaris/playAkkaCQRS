package net.imadz.application.services.transactor

import akka.actor.typed.ActorRef
import net.imadz.common.CborSerializable
import net.imadz.common.CommonTypes.Id
import net.imadz.infra.saga.SagaTransactionCoordinator
import net.imadz.infra.saga.SagaTransactionCoordinator.TracingStep

object FabSagaProtocol {

  // --- Commands ---
  sealed trait FabSagaCommand extends CborSerializable

  case class InitiateWaferTransfer(
    sourceLotId: Id,
    targetLotId: Id,
    waferIds: Set[Id],
    replyTo: ActorRef[FabSagaConfirmation]
  ) extends FabSagaCommand

  case class InitiateLotSplit(
    sourceLotId: Id,
    targetLotId: Id,
    waferIds: Set[Id],
    replyTo: ActorRef[FabSagaConfirmation]
  ) extends FabSagaCommand

  case class InitiateLotMerge(
    sourceLotIds: List[Id],
    targetLotId: Id,
    waferIds: Set[Id],
    replyTo: ActorRef[FabSagaConfirmation]
  ) extends FabSagaCommand

  // Internal: receives async result from Saga Coordinator
  case class UpdateFabSagaStatus(
    id: Id,
    newStatus: SagaTransactionCoordinator.TransactionResult,
    replyTo: ActorRef[FabSagaConfirmation]
  ) extends FabSagaCommand

  // Admin
  case class AdminManualFixStep(
    stepId: String,
    phase: String,
    replyTo: ActorRef[FabSagaConfirmation]
  ) extends FabSagaCommand

  case class AdminResumeTransaction(
    replyTo: ActorRef[FabSagaConfirmation]
  ) extends FabSagaCommand

  // --- Replies ---
  case class FabSagaConfirmation(
    transactionId: Id,
    error: Option[String],
    tracing: List[TracingStep] = Nil
  ) extends CborSerializable
}
