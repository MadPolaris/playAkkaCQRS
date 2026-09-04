package net.imadz.application.aggregates

import akka.actor.typed.ActorRef
import akka.persistence.typed.scaladsl.Effect
import net.imadz.common.CborSerializable
import net.imadz.domain.entities.FabProcessEntity.{FabProcessEvent, FabProcessState}

object FabProcessProtocol {

  // --- Commands ---
  sealed trait FabProcessCommand extends CborSerializable

  case class StartProcess(lotId: String, waferIds: Set[String], lotSize: Int, replyTo: ActorRef[ProcessConfirmation]) extends FabProcessCommand
  case class RecordFoupLoaded(foupId: String, stockerId: String, replyTo: ActorRef[ProcessConfirmation]) extends FabProcessCommand
  case class RecordTransportStarted(foupId: String, fromArea: String, toArea: String, estimatedMs: Long, replyTo: ActorRef[ProcessConfirmation]) extends FabProcessCommand
  case class RecordTransportCompleted(foupId: String, equipmentId: String, replyTo: ActorRef[ProcessConfirmation]) extends FabProcessCommand
  case class RecordEquipmentJobStarted(equipmentId: String, recipeId: String, replyTo: ActorRef[ProcessConfirmation]) extends FabProcessCommand
  case class RecordEquipmentJobCompleted(equipmentId: String, jobId: String, success: Boolean, replyTo: ActorRef[ProcessConfirmation]) extends FabProcessCommand
  case class RecordWaferMeasured(waferId: String, cdNm: Double, replyTo: ActorRef[ProcessConfirmation]) extends FabProcessCommand
  case class RecordWaferClassified(waferId: String, classification: String, reworkCount: Int, cdValue: Double, replyTo: ActorRef[ProcessConfirmation]) extends FabProcessCommand
  case class RecordWafersSplitForRework(reworkWaferIds: Set[String], scrapWaferIds: Set[String], iteration: Int, replyTo: ActorRef[ProcessConfirmation]) extends FabProcessCommand
  case class RecordWafersReworked(waferIds: Set[String], replyTo: ActorRef[ProcessConfirmation]) extends FabProcessCommand
  case class RecordWafersSentAsPilot(waferIds: Set[String], replyTo: ActorRef[ProcessConfirmation]) extends FabProcessCommand
  case class RecordWafersSampled(sampleIds: Set[String], skipIds: Set[String], replyTo: ActorRef[ProcessConfirmation]) extends FabProcessCommand
  case class RecordWafersHeld(waferIds: Set[String], reason: String, replyTo: ActorRef[ProcessConfirmation]) extends FabProcessCommand
  case class RecordWafersReleased(waferIds: Set[String], replyTo: ActorRef[ProcessConfirmation]) extends FabProcessCommand
  case class CompleteProcess(lotId: String, passCount: Int, scrapCount: Int, reworkCount: Int, replyTo: ActorRef[ProcessConfirmation]) extends FabProcessCommand

  // --- Reply ---
  case class ProcessConfirmation(processId: String, phase: String) extends CborSerializable

  // --- Handler Type ---
  type FabProcessCommandHandler = (FabProcessState, FabProcessCommand) => Effect[FabProcessEvent, FabProcessState]
}
