package net.imadz.infrastructure.persistence.converters

import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.application.services.transactor.{FromAccountParticipant, ToAccountParticipant}
import net.imadz.infrastructure.proto.saga_participant.{FromAccountParticipantPO, ToAccountParticipantPO}

import scala.concurrent.ExecutionContext

trait SagaProtoConverters extends CreditBalanceProtoConverters {

  case class FromAccountParticipantConv(repository: CreditBalanceRepository)(implicit ec: ExecutionContext) extends ProtoConverter[FromAccountParticipant, FromAccountParticipantPO] {
    override def toProto(d: FromAccountParticipant): FromAccountParticipantPO = FromAccountParticipantPO(
      fromUserId = IdConv.toProto(d.fromUserId),
      amount = Some(MoneyConv.toProto(d.amount))
    )

    override def fromProto(p: FromAccountParticipantPO): FromAccountParticipant = {
      p.amount.map(amount => {
        FromAccountParticipant(IdConv.fromProto(p.fromUserId), amount = MoneyConv.fromProto(amount))
      }).getOrElse(throw new IllegalArgumentException(s"FromAccountParticipantPO should have amount property value, but is ${p.amount}"))
    }

  }

  case class ToAccountParticipantConv(repository: CreditBalanceRepository)(implicit ec: ExecutionContext) extends ProtoConverter[ToAccountParticipant, ToAccountParticipantPO] {

    override def toProto(domain: ToAccountParticipant): ToAccountParticipantPO = ToAccountParticipantPO(
      toUserId = IdConv.toProto(domain.toUserId),
      amount = Some(MoneyConv.toProto(domain.amount))
    )

    override def fromProto(proto: ToAccountParticipantPO): ToAccountParticipant = proto.amount.map(amount => {
      ToAccountParticipant(
        toUserId = IdConv.fromProto(proto.toUserId),
        amount = MoneyConv.fromProto(amount)
      )
    }).getOrElse(throw new IllegalArgumentException(s"ToAccountParticipantPO should have amount property value, but is ${proto.amount}"))
  }
}