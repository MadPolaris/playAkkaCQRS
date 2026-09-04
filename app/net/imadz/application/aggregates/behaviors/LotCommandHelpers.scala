package net.imadz.application.aggregates.behaviors

import net.imadz.application.aggregates.LotProtocol._
import net.imadz.common.CommonTypes.{Id, iMadzError}
import net.imadz.common.application.CommandHandlerReplyingBehavior.CommandHelper
import net.imadz.domain.entities.LotEntity._

trait LotCommandHelpers {

  implicit object CreateLotHelper extends CommandHelper[CreateLot, LotState, (String, Map[Id, String], Option[Id], Option[SplitReason], Option[String]), LotConfirmation] {
    override def toParam(state: LotState, command: CreateLot): (String, Map[Id, String], Option[Id], Option[SplitReason], Option[String]) = (command.productId, command.waferNames, command.parentLotId, command.splitReason, command.workOrderId)
    override def createFailureReply(param: (String, Map[Id, String], Option[Id], Option[SplitReason], Option[String]))(error: iMadzError): LotConfirmation = LotConfirmation(Some(error))
    override def createSuccessReply(param: (String, Map[Id, String], Option[Id], Option[SplitReason], Option[String]))(state: LotState): LotConfirmation = LotConfirmation(None, state.waferIds, Some(state.phase))
  }

  implicit object SealLotHelper extends CommandHelper[SealLot, LotState, Unit, LotConfirmation] {
    override def toParam(state: LotState, command: SealLot): Unit = ()
    override def createFailureReply(param: Unit)(error: iMadzError): LotConfirmation = LotConfirmation(Some(error))
    override def createSuccessReply(param: Unit)(state: LotState): LotConfirmation = LotConfirmation(None, state.waferIds, Some(state.phase))
  }

  implicit object ReserveWaferRemovalHelper extends CommandHelper[ReserveWaferRemoval, LotState, (Id, Set[Id], Set[String]), WaferRemovalConfirmation] {
    override def toParam(state: LotState, command: ReserveWaferRemoval): (Id, Set[Id], Set[String]) = (command.transferId, command.waferIds, command.waferNames)
    override def createFailureReply(param: (Id, Set[Id], Set[String]))(error: iMadzError): WaferRemovalConfirmation = WaferRemovalConfirmation(param._1, Some(error))
    override def createSuccessReply(param: (Id, Set[Id], Set[String]))(state: LotState): WaferRemovalConfirmation = WaferRemovalConfirmation(param._1, None)
  }

  implicit object CommitWaferRemovalHelper extends CommandHelper[CommitWaferRemoval, LotState, Id, WaferRemovalConfirmation] {
    override def toParam(state: LotState, command: CommitWaferRemoval): Id = command.transferId
    override def createFailureReply(param: Id)(error: iMadzError): WaferRemovalConfirmation = WaferRemovalConfirmation(param, Some(error))
    override def createSuccessReply(param: Id)(state: LotState): WaferRemovalConfirmation = WaferRemovalConfirmation(param, None)
  }

  implicit object ReleaseReservedWaferHelper extends CommandHelper[ReleaseReservedWafer, LotState, Id, WaferRemovalConfirmation] {
    override def toParam(state: LotState, command: ReleaseReservedWafer): Id = command.transferId
    override def createFailureReply(param: Id)(error: iMadzError): WaferRemovalConfirmation = WaferRemovalConfirmation(param, Some(error))
    override def createSuccessReply(param: Id)(state: LotState): WaferRemovalConfirmation = WaferRemovalConfirmation(param, None)
  }

  implicit object ReserveAddWaferHelper extends CommandHelper[ReserveAddWafer, LotState, (Id, Set[Id]), WaferAdditionConfirmation] {
    override def toParam(state: LotState, command: ReserveAddWafer): (Id, Set[Id]) = (command.transferId, command.waferIds)
    override def createFailureReply(param: (Id, Set[Id]))(error: iMadzError): WaferAdditionConfirmation = WaferAdditionConfirmation(param._1, Some(error))
    override def createSuccessReply(param: (Id, Set[Id]))(state: LotState): WaferAdditionConfirmation = WaferAdditionConfirmation(param._1, None)
  }

  implicit object CommitAddWaferHelper extends CommandHelper[CommitAddWafer, LotState, Id, WaferAdditionConfirmation] {
    override def toParam(state: LotState, command: CommitAddWafer): Id = command.transferId
    override def createFailureReply(param: Id)(error: iMadzError): WaferAdditionConfirmation = WaferAdditionConfirmation(param, Some(error))
    override def createSuccessReply(param: Id)(state: LotState): WaferAdditionConfirmation = WaferAdditionConfirmation(param, None)
  }

  implicit object CancelAddWaferHelper extends CommandHelper[CancelAddWafer, LotState, Id, WaferAdditionConfirmation] {
    override def toParam(state: LotState, command: CancelAddWafer): Id = command.transferId
    override def createFailureReply(param: Id)(error: iMadzError): WaferAdditionConfirmation = WaferAdditionConfirmation(param, Some(error))
    override def createSuccessReply(param: Id)(state: LotState): WaferAdditionConfirmation = WaferAdditionConfirmation(param, None)
  }

  // RouteCard helpers (M3.5+)
  implicit object AssignRouteCardHelper extends CommandHelper[AssignRouteCard, LotState, (Seq[String], Option[String], String), LotConfirmation] {
    override def toParam(state: LotState, command: AssignRouteCard): (Seq[String], Option[String], String) = (command.steps, command.sourcedFrom, command.reason)
    override def createFailureReply(param: (Seq[String], Option[String], String))(error: iMadzError): LotConfirmation = LotConfirmation(Some(error))
    override def createSuccessReply(param: (Seq[String], Option[String], String))(state: LotState): LotConfirmation = LotConfirmation(None, state.waferIds, Some(state.phase))
  }

  implicit object AdvanceRouteCardStepHelper extends CommandHelper[AdvanceRouteCardStep, LotState, Int, LotConfirmation] {
    override def toParam(state: LotState, command: AdvanceRouteCardStep): Int = command.stepIndex
    override def createFailureReply(param: Int)(error: iMadzError): LotConfirmation = LotConfirmation(Some(error))
    override def createSuccessReply(param: Int)(state: LotState): LotConfirmation = LotConfirmation(None, state.waferIds, Some(state.phase))
  }
}
