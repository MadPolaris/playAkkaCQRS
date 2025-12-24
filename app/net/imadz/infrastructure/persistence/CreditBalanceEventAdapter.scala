package net.imadz.infrastructure.persistence

import akka.persistence.typed.{EventAdapter, EventSeq}
import net.imadz.domain.entities.CreditBalanceEntity._
// 引入生成的 Converter Trait
import net.imadz.infrastructure.persistence.converters.CreditBalanceProtoConverters
// 引入 Protobuf 定义
import net.imadz.infrastructure.proto.credits._

/**
 * CreditBalanceEventAdapter
 * * 职责：
 * 1. 混入 CreditBalanceProtoConverters 以获得所有转化能力
 * 2. 将领域事件映射到 Proto OneOf 包装类
 * 3. 将 Proto OneOf 包装类还原回领域事件
 */
class CreditBalanceEventAdapter extends EventAdapter[CreditBalanceEvent, CreditBalanceEventPO.Event]
  with CreditBalanceProtoConverters {

  override def manifest(event: CreditBalanceEvent): String = event.getClass.getName

  override def toJournal(e: CreditBalanceEvent): CreditBalanceEventPO.Event = {
    // 显式调用 Converters 中定义的 Object，逻辑清晰，无隐式魔法
    val eventUnion = e match {
      case evt: BalanceChanged =>
        CreditBalanceEventPO.Event.BalanceChanged(BalanceChangedConv.toProto(evt))

      case evt: FundsReserved =>
        CreditBalanceEventPO.Event.FundsReserved(FundsReservedConv.toProto(evt))

      case evt: FundsDeducted =>
        CreditBalanceEventPO.Event.FundsDeducted(FundsDeductedConv.toProto(evt))

      case evt: ReservationReleased =>
        CreditBalanceEventPO.Event.ReservationReleased(ReservationReleasedConv.toProto(evt))

      case evt: IncomingCreditsRecorded =>
        CreditBalanceEventPO.Event.IncomingCreditsRecorded(IncomingCreditsRecordedConv.toProto(evt))

      case evt: IncomingCreditsCommited =>
        CreditBalanceEventPO.Event.IncomingCreditsCommited(IncomingCreditsCommitedConv.toProto(evt))

      case evt: IncomingCreditsCanceled =>
        CreditBalanceEventPO.Event.IncomingCreditsCanceled(IncomingCreditsCanceledConv.toProto(evt))
    }

    eventUnion
  }

  override def fromJournal(p: CreditBalanceEventPO.Event, manifest: String): EventSeq[CreditBalanceEvent] = {
    p match {
      case CreditBalanceEventPO.Event.BalanceChanged(po) =>
        EventSeq.single(BalanceChangedConv.fromProto(po))

      case CreditBalanceEventPO.Event.FundsReserved(po) =>
        EventSeq.single(FundsReservedConv.fromProto(po))

      case CreditBalanceEventPO.Event.FundsDeducted(po) =>
        EventSeq.single(FundsDeductedConv.fromProto(po))

      case CreditBalanceEventPO.Event.ReservationReleased(po) =>
        EventSeq.single(ReservationReleasedConv.fromProto(po))

      case CreditBalanceEventPO.Event.IncomingCreditsRecorded(po) =>
        EventSeq.single(IncomingCreditsRecordedConv.fromProto(po))

      case CreditBalanceEventPO.Event.IncomingCreditsCommited(po) =>
        EventSeq.single(IncomingCreditsCommitedConv.fromProto(po))

      case CreditBalanceEventPO.Event.IncomingCreditsCanceled(po) =>
        EventSeq.single(IncomingCreditsCanceledConv.fromProto(po))

      case CreditBalanceEventPO.Event.Empty =>
        EventSeq.empty
    }
  }
}