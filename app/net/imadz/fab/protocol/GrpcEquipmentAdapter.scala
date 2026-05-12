package net.imadz.fab.protocol

import scala.concurrent.{ExecutionContext, Future}

/**
 * gRPC-based equipment adapter stub.
 *
 * In a production Fab, equipment communicates via SEMI SECS/GEM protocol.
 * This adapter would use gRPC as the transport layer, with protobuf-encoded
 * SECS-II messages. Each EquipmentCommand/EquipmentEvent would be serialized
 * to/from protobuf messages defined in `.proto` service definitions.
 *
 * For now, this is a stub that delegates to an in-process simulator.
 * Replace with actual gRPC stubs when equipment EAP endpoints are available.
 *
 * Future protobuf service definition sketch:
 * {{{
 *   service EquipmentService {
 *     rpc SendCommand (EquipmentCommandMessage) returns (EquipmentEventMessage);
 *     rpc StreamEvents (EquipmentId) returns (stream EquipmentEventMessage);
 *   }
 * }}}
 */
class GrpcEquipmentAdapter(
  endpoint: String,
  fallback: ActorEquipmentAdapter
)(implicit ec: ExecutionContext) extends EquipmentAdapter[Future] {

  override def adapterId: String = s"grpc-$endpoint"

  override def sendCommand(equipmentId: String, cmd: EquipmentCommand): Future[EquipmentEvent] = {
    // TODO: when gRPC is available, serialize cmd → protobuf, call gRPC endpoint
    // For now, delegate to the in-process fallback
    fallback.sendCommand(equipmentId, cmd)
  }

  override def queryStatus(equipmentId: String): Future[StatusReport] = {
    fallback.queryStatus(equipmentId)
  }

  override def subscribe(equipmentId: String)(callback: EquipmentEvent => Unit): Unit = {
    // TODO: connect to gRPC server-side streaming for equipment events
    fallback.subscribe(equipmentId)(callback)
  }

  override def unsubscribe(equipmentId: String): Unit = {
    fallback.unsubscribe(equipmentId)
  }
}
