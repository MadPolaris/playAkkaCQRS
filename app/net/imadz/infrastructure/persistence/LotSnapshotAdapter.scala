package net.imadz.infrastructure.persistence

import akka.persistence.typed.SnapshotAdapter
import net.imadz.domain.entities.LotEntity._
import net.imadz.infrastructure.persistence.converters.LotProtoConverters
import net.imadz.infrastructure.proto.lot.LotStatePO

class LotSnapshotAdapter extends SnapshotAdapter[LotState] with LotProtoConverters {

  override def toJournal(state: LotState): Any = LotStateConv.toProto(state)

  override def fromJournal(from: Any): LotState = from match {
    case po: LotStatePO => LotStateConv.fromProto(po)
    case unknown => throw new IllegalStateException(s"Unknown journal type: ${unknown.getClass.getName}")
  }
}
