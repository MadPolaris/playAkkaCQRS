package net.imadz.fab.protocol

import scala.concurrent.Future

/**
 * Pluggable equipment communication adapter.
 *
 * Separates equipment communication protocol from business logic.
 * Implementations: ActorEquipmentAdapter (in-process simulation),
 * GrpcEquipmentAdapter (future: real equipment via gRPC).
 *
 * @tparam F effect type — Future for async, can be IO for pure FP later
 */
trait EquipmentAdapter[F[_]] {

  /** Human-readable identifier for diagnostics */
  def adapterId: String

  /** Send a command to specific equipment, wait for the reply event */
  def sendCommand(equipmentId: String, cmd: EquipmentCommand): F[EquipmentEvent]

  /** Query equipment status without side effects */
  def queryStatus(equipmentId: String): Future[StatusReport]

  /** Register a callback for unsolicited events pushed by equipment */
  def subscribe(equipmentId: String)(callback: EquipmentEvent => Unit): Unit

  /** Unregister a previously registered callback */
  def unsubscribe(equipmentId: String): Unit
}
