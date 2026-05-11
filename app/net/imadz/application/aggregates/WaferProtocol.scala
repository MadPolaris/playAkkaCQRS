package net.imadz.application.aggregates

import akka.actor.typed.ActorRef
import akka.persistence.typed.scaladsl.Effect
import net.imadz.common.CborSerializable
import net.imadz.common.CommonTypes.{Id, iMadzError}
import net.imadz.domain.entities.WaferEntity.{WaferEvent, WaferState, WaferStatus}

object WaferProtocol {

  // --- Commands ---
  sealed trait WaferCommand extends CborSerializable

  // Lifecycle
  case class CreateWafer(lotId: Id, replyTo: ActorRef[WaferConfirmation]) extends WaferCommand
  case class GetWaferState(replyTo: ActorRef[WaferConfirmation]) extends WaferCommand

  // Transfer (ownership change — for Saga)
  case class ReserveTransfer(transferId: Id, targetLotId: Id, replyTo: ActorRef[TransferConfirmation]) extends WaferCommand
  case class CommitTransfer(transferId: Id, targetLotId: Id, replyTo: ActorRef[TransferConfirmation]) extends WaferCommand
  case class ReleaseTransfer(transferId: Id, replyTo: ActorRef[TransferConfirmation]) extends WaferCommand

  // Status changes
  case class ScrapWafer(reason: String, replyTo: ActorRef[WaferConfirmation]) extends WaferCommand
  case class ChangeStatus(newStatus: WaferStatus, replyTo: ActorRef[WaferConfirmation]) extends WaferCommand

  // --- Replies ---
  case class WaferConfirmation(error: Option[iMadzError], status: Option[WaferStatus] = None, lotId: Option[Id] = None) extends CborSerializable
  case class TransferConfirmation(transferId: Id, error: Option[iMadzError]) extends CborSerializable

  // --- Handler Type ---
  type WaferCommandHandler = (WaferState, WaferCommand) => Effect[WaferEvent, WaferState]
}
