package net.imadz.fab.aggregate

import akka.actor.typed.ActorRef
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import net.imadz.fab.protocol._

/**
 * Lightweight equipment state tracker — not a full CQRS aggregate,
 * but sufficient for the demo to show real-time equipment status
 * on the factory floor visualization.
 */
object EquipmentAggregate {

  val EntityKey: EntityTypeKey[EqCommand] = EntityTypeKey("FabEquipment")

  // --- Commands ---
  sealed trait EqCommand
  case class UpdateStatus(report: StatusReport) extends EqCommand
  case class GetState(replyTo: ActorRef[EquipmentState]) extends EqCommand
  case class InjectFault(faultType: String) extends EqCommand
  case class ClearFault() extends EqCommand
  case class IncrementProcessed() extends EqCommand
  case class IncrementFailed() extends EqCommand

  // --- State ---
  case class EquipmentState(
    equipmentId: String,
    status: String = "Idle",
    currentJob: Option[String] = None,
    currentFoup: Option[String] = None,
    wafersProcessed: Int = 0,
    wafersFailed: Int = 0,
    faultMode: Option[String] = None
  ) {
    def withStatus(s: String): EquipmentState = copy(status = s)
    def withJob(j: String): EquipmentState = copy(currentJob = Some(j), status = "Busy")
    def clearJob(): EquipmentState = copy(currentJob = None)
  }

  def empty(equipmentId: String): EquipmentState = EquipmentState(equipmentId)
}
