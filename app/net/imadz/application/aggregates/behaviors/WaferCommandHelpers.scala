package net.imadz.application.aggregates.behaviors

import net.imadz.application.aggregates.WaferProtocol._
import net.imadz.common.CommonTypes.{Id, iMadzError}
import net.imadz.common.application.CommandHandlerReplyingBehavior.CommandHelper
import net.imadz.domain.entities.WaferEntity._

trait WaferCommandHelpers {

  implicit object CreateWaferHelper extends CommandHelper[CreateWafer, WaferState, Id, WaferConfirmation] {
    override def toParam(state: WaferState, command: CreateWafer): Id = command.lotId
    override def createFailureReply(param: Id)(error: iMadzError): WaferConfirmation = WaferConfirmation(Some(error))
    override def createSuccessReply(param: Id)(state: WaferState): WaferConfirmation = WaferConfirmation(None, Some(state.status), state.lotId)
  }

  implicit object ScrapWaferHelper extends CommandHelper[ScrapWafer, WaferState, String, WaferConfirmation] {
    override def toParam(state: WaferState, command: ScrapWafer): String = command.reason
    override def createFailureReply(param: String)(error: iMadzError): WaferConfirmation = WaferConfirmation(Some(error))
    override def createSuccessReply(param: String)(state: WaferState): WaferConfirmation = WaferConfirmation(None, Some(state.status), state.lotId)
  }

  implicit object ChangeStatusHelper extends CommandHelper[ChangeStatus, WaferState, WaferStatus, WaferConfirmation] {
    override def toParam(state: WaferState, command: ChangeStatus): WaferStatus = command.newStatus
    override def createFailureReply(param: WaferStatus)(error: iMadzError): WaferConfirmation = WaferConfirmation(Some(error))
    override def createSuccessReply(param: WaferStatus)(state: WaferState): WaferConfirmation = WaferConfirmation(None, Some(state.status), state.lotId)
  }

  implicit object ReserveTransferHelper extends CommandHelper[ReserveTransfer, WaferState, (Id, Id), TransferConfirmation] {
    override def toParam(state: WaferState, command: ReserveTransfer): (Id, Id) = (command.transferId, command.targetLotId)
    override def createFailureReply(param: (Id, Id))(error: iMadzError): TransferConfirmation = TransferConfirmation(param._1, Some(error))
    override def createSuccessReply(param: (Id, Id))(state: WaferState): TransferConfirmation = TransferConfirmation(param._1, None)
  }

  implicit object CommitTransferHelper extends CommandHelper[CommitTransfer, WaferState, (Id, Id), TransferConfirmation] {
    override def toParam(state: WaferState, command: CommitTransfer): (Id, Id) = (command.transferId, command.targetLotId)
    override def createFailureReply(param: (Id, Id))(error: iMadzError): TransferConfirmation = TransferConfirmation(param._1, Some(error))
    override def createSuccessReply(param: (Id, Id))(state: WaferState): TransferConfirmation = TransferConfirmation(param._1, None)
  }

  implicit object ReleaseTransferHelper extends CommandHelper[ReleaseTransfer, WaferState, Id, TransferConfirmation] {
    override def toParam(state: WaferState, command: ReleaseTransfer): Id = command.transferId
    override def createFailureReply(param: Id)(error: iMadzError): TransferConfirmation = TransferConfirmation(param, Some(error))
    override def createSuccessReply(param: Id)(state: WaferState): TransferConfirmation = TransferConfirmation(param, None)
  }

  implicit object HoldWaferHelper extends CommandHelper[HoldWafer, WaferState, String, WaferConfirmation] {
    override def toParam(state: WaferState, command: HoldWafer): String = command.reason
    override def createFailureReply(param: String)(error: iMadzError): WaferConfirmation = WaferConfirmation(Some(error))
    override def createSuccessReply(param: String)(state: WaferState): WaferConfirmation = WaferConfirmation(None, Some(state.status), state.lotId)
  }

  implicit object ReleaseHoldHelper extends CommandHelper[ReleaseHold, WaferState, Unit, WaferConfirmation] {
    override def toParam(state: WaferState, command: ReleaseHold): Unit = ()
    override def createFailureReply(param: Unit)(error: iMadzError): WaferConfirmation = WaferConfirmation(Some(error))
    override def createSuccessReply(param: Unit)(state: WaferState): WaferConfirmation = WaferConfirmation(None, Some(state.status), state.lotId)
  }

  implicit object SkipWaferHelper extends CommandHelper[SkipWafer, WaferState, String, WaferConfirmation] {
    override def toParam(state: WaferState, command: SkipWafer): String = command.reason
    override def createFailureReply(param: String)(error: iMadzError): WaferConfirmation = WaferConfirmation(Some(error))
    override def createSuccessReply(param: String)(state: WaferState): WaferConfirmation = WaferConfirmation(None, Some(state.status), state.lotId)
  }
}
