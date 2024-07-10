package net.imadz.infrastructure.persistence

import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.application.services.transactor.MoneyTransferSagaTransactor.{FromAccountParticipant, ToAccountParticipant}
import net.imadz.common.CommonTypes.iMadzError
import net.imadz.common.Id
import net.imadz.domain.values.Money
import net.imadz.infra.saga.proto.saga_v2.{SagaParticipantPO => ParticipantPO}
import net.imadz.infra.saga.{ForSaga, SagaParticipant}
import net.imadz.infrastructure.proto.credits.MoneyPO
import net.imadz.infrastructure.proto.saga_participant.{FromAccountParticipantPO, ToAccountParticipantPO}

import java.util.Currency
import scala.concurrent.ExecutionContext

trait ParticipantAdapter extends ForSaga {
  def repository: CreditBalanceRepository

  def ec: ExecutionContext

  def deserializeParticipant(participantPO: ParticipantPO): SagaParticipant[iMadzError, String] = participantPO.participant match {
    case ParticipantPO.Participant.FromAccount(fromAccountPO) =>
      FromAccountParticipant(
        fromUserId = Id.of(fromAccountPO.fromUserId),
        amount = Money(
          amount = BigDecimal(fromAccountPO.amount.get.amount),
          currency = Currency.getInstance(fromAccountPO.amount.get.currency)
        ),
        repo = repository)(ec)

    case ParticipantPO.Participant.ToAccount(fromAccountPO) =>
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
        participant = ParticipantPO.Participant.FromAccount(
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
        participant = ParticipantPO.Participant.ToAccount(
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
