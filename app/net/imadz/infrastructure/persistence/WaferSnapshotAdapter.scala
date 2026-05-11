package net.imadz.infrastructure.persistence

import akka.persistence.typed.SnapshotAdapter
import net.imadz.domain.entities.WaferEntity._
import net.imadz.infrastructure.persistence.converters.WaferProtoConverters
import net.imadz.infrastructure.proto.wafer.WaferStatePO

class WaferSnapshotAdapter extends SnapshotAdapter[WaferState] with WaferProtoConverters {

  override def toJournal(state: WaferState): Any = WaferStateConv.toProto(state)

  override def fromJournal(from: Any): WaferState = from match {
    case po: WaferStatePO => WaferStateConv.fromProto(po)
    case unknown => throw new IllegalStateException(s"Unknown journal type: ${unknown.getClass.getName}")
  }
}
