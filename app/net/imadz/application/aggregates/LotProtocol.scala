package net.imadz.application.aggregates

import akka.actor.typed.ActorRef
import akka.persistence.typed.scaladsl.Effect
import net.imadz.common.CborSerializable
import net.imadz.common.CommonTypes.{Id, iMadzError}
import net.imadz.domain.entities.LotEntity.{LotEvent, LotPhase, LotState}

object LotProtocol {

  // --- Commands ---
  sealed trait LotCommand extends CborSerializable

  // Lifecycle
  case class CreateLot(productId: String, waferIds: Set[Id], replyTo: ActorRef[LotConfirmation]) extends LotCommand
  case class GetLotState(replyTo: ActorRef[LotConfirmation]) extends LotCommand
  case class SealLot(replyTo: ActorRef[LotConfirmation]) extends LotCommand

  // Source lot (outgoing wafer reservation — for Saga)
  case class ReserveWaferRemoval(transferId: Id, waferIds: Set[Id], replyTo: ActorRef[WaferRemovalConfirmation]) extends LotCommand
  case class CommitWaferRemoval(transferId: Id, replyTo: ActorRef[WaferRemovalConfirmation]) extends LotCommand
  case class ReleaseReservedWafer(transferId: Id, replyTo: ActorRef[WaferRemovalConfirmation]) extends LotCommand

  // Target lot (incoming wafer reservation — for Saga)
  case class ReserveAddWafer(transferId: Id, waferIds: Set[Id], replyTo: ActorRef[WaferAdditionConfirmation]) extends LotCommand
  case class CommitAddWafer(transferId: Id, replyTo: ActorRef[WaferAdditionConfirmation]) extends LotCommand
  case class CancelAddWafer(transferId: Id, replyTo: ActorRef[WaferAdditionConfirmation]) extends LotCommand

  // --- Replies ---
  case class LotConfirmation(error: Option[iMadzError], waferIds: Set[Id] = Set.empty, phase: Option[LotPhase] = None) extends CborSerializable
  case class WaferRemovalConfirmation(transferId: Id, error: Option[iMadzError]) extends CborSerializable
  case class WaferAdditionConfirmation(transferId: Id, error: Option[iMadzError]) extends CborSerializable

  // --- Handler Type ---
  type LotCommandHandler = (LotState, LotCommand) => Effect[LotEvent, LotState]
}
