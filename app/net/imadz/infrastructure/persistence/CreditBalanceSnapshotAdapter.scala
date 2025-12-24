package net.imadz.infrastructure.persistence

import akka.persistence.typed.SnapshotAdapter
import net.imadz.domain.entities.CreditBalanceEntity._
import net.imadz.infrastructure.persistence.converters.CreditBalanceProtoConverters
import net.imadz.infrastructure.proto.credits.CreditBalanceStatePO

class CreditBalanceSnapshotAdapter extends SnapshotAdapter[CreditBalanceState] with CreditBalanceProtoConverters {

  override def toJournal(state: CreditBalanceState): Any = {
    CreditBalanceStateConv.toProto(state)
  }

  override def fromJournal(from: Any): CreditBalanceState = from match {
    case po: CreditBalanceStatePO =>
      CreditBalanceStateConv.fromProto(po)
    case unknown => throw new IllegalStateException(s"Unknown journal type: ${unknown.getClass.getName}")
  }

}