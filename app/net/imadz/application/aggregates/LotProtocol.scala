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

  // Equipment reports (fire-and-forget with replyTo for confirmation)
  case class RecordFoupLoaded(foupId: String, stockerId: String, replyTo: ActorRef[LotConfirmation]) extends LotCommand
  case class RecordTransportStarted(foupId: String, fromArea: String, toArea: String, estimatedMs: Long, replyTo: ActorRef[LotConfirmation]) extends LotCommand
  case class RecordTransportCompleted(foupId: String, equipmentId: String, replyTo: ActorRef[LotConfirmation]) extends LotCommand
  case class RecordEquipmentJobStarted(equipmentId: String, recipeId: String, replyTo: ActorRef[LotConfirmation]) extends LotCommand
  case class RecordEquipmentJobCompleted(equipmentId: String, jobId: String, success: Boolean, replyTo: ActorRef[LotConfirmation]) extends LotCommand
  case class RecordWaferMeasured(waferId: String, cdNm: Double, replyTo: ActorRef[LotConfirmation]) extends LotCommand
  case class RecordWaferClassified(waferId: String, classification: String, reworkCount: Int, cdValue: Double, replyTo: ActorRef[LotConfirmation]) extends LotCommand
  case class RecordWafersSplitForRework(reworkWaferIds: Set[String], scrapWaferIds: Set[String], iteration: Int, replyTo: ActorRef[LotConfirmation]) extends LotCommand
  case class RecordWafersReworked(waferIds: Set[String], replyTo: ActorRef[LotConfirmation]) extends LotCommand
  case class RecordWafersSentAsPilot(waferIds: Set[String], replyTo: ActorRef[LotConfirmation]) extends LotCommand
  case class RecordWafersSampled(sampleIds: Set[String], skipIds: Set[String], replyTo: ActorRef[LotConfirmation]) extends LotCommand
  case class RecordWafersHeld(waferIds: Set[String], reason: String, replyTo: ActorRef[LotConfirmation]) extends LotCommand
  case class RecordWafersReleased(waferIds: Set[String], replyTo: ActorRef[LotConfirmation]) extends LotCommand
  case class CompleteProcess(lotId: String, passCount: Int, scrapCount: Int, reworkCount: Int, replyTo: ActorRef[LotConfirmation]) extends LotCommand

  // --- Replies ---
  case class LotConfirmation(error: Option[iMadzError], waferIds: Set[Id] = Set.empty, phase: Option[LotPhase] = None,
                            // Full state fields populated by GetLotState
                            productId: Option[String] = None,
                            lotId: Option[Id] = None,
                            reservedWafers: Map[Id, Set[Id]] = Map.empty,
                            incomingWafers: Map[Id, Set[Id]] = Map.empty,
                            completedTransferIds: Set[Id] = Set.empty,
                            areaVisitHistory: List[String] = Nil,
                            routingStepReentry: Map[String, Int] = Map.empty,
                            loadedFoupId: Option[String] = None,
                            waferClassifications: Map[String, String] = Map.empty,
                            completedJobs: Set[String] = Set.empty,
                            measuredWafers: Set[String] = Set.empty,
                            currentStepIndex: Int = 0) extends CborSerializable
  case class WaferRemovalConfirmation(transferId: Id, error: Option[iMadzError]) extends CborSerializable
  case class WaferAdditionConfirmation(transferId: Id, error: Option[iMadzError]) extends CborSerializable

  // --- Handler Type ---
  type LotCommandHandler = (LotState, LotCommand) => Effect[LotEvent, LotState]
}
