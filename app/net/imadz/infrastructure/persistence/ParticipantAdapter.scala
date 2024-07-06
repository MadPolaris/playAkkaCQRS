package net.imadz.infrastructure.persistence

import net.imadz.application.aggregates.MoneyTransferTransactionAggregate.{FromAccountParticipant, ToAccountParticipant}
import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.common.CommonTypes.iMadzError
import net.imadz.common.Id
import net.imadz.domain.values.Money
import net.imadz.infra.saga.SagaParticipant
import net.imadz.infrastructure.proto.credits.MoneyPO
import net.imadz.infrastructure.proto.saga_participant.{FromAccountParticipantPO, ToAccountParticipantPO}
import net.imadz.infrastructure.saga.proto.saga.ParticipantPO

import java.util.Currency
import scala.concurrent.ExecutionContext

trait ParticipantAdapter {
  def repository: CreditBalanceRepository
  def ec: ExecutionContext

  def deserializeParticipant(participantPO: ParticipantPO): SagaParticipant[iMadzError, String] = participantPO.participantType match {
    case ParticipantPO.ParticipantType.FromAccountParticipant(fromAccountPO) =>
      FromAccountParticipant(
        fromUserId = Id.of(fromAccountPO.fromUserId),
        amount = Money(
          amount = BigDecimal(fromAccountPO.amount.get.amount),
          currency = Currency.getInstance(fromAccountPO.amount.get.currency)
        ),
        repo = repository)(ec)

    case ParticipantPO.ParticipantType.ToAccountParticipant(fromAccountPO) =>
      ToAccountParticipant(
        toUserId = Id.of(fromAccountPO.toUserId),
        amount = Money(
          amount = BigDecimal(fromAccountPO.amount.get.amount),
          currency = Currency.getInstance(fromAccountPO.amount.get.currency)
        ),
        repo = repository)(ec)
  }

  def serializeParticipant(participant: SagaParticipant[iMadzError, String]): Option[ParticipantPO] = participant match {
    case FromAccountParticipant(fromUserId, amount, _) =>
      Some(ParticipantPO(
        name = "FromAccountParticipant",
        participantType = ParticipantPO.ParticipantType.FromAccountParticipant(
          FromAccountParticipantPO(
            fromUserId = fromUserId.toString,
            amount = Some(MoneyPO(
              amount = amount.amount.longValue,
              currency = amount.currency.getCurrencyCode
            ))
          )
        )
      ))

    case ToAccountParticipant(toUserId, amount, _) =>
      Some(ParticipantPO(
        name = "ToAccountParticipant",
        participantType = ParticipantPO.ParticipantType.ToAccountParticipant(
          ToAccountParticipantPO(
            toUserId = toUserId.toString,
            amount = Some(MoneyPO(
              amount = amount.amount.longValue,
              currency = amount.currency.getCurrencyCode
            ))
          )
        )
      ))

    case _ => None
  }
}
