package net.imadz.infra.saga.serialization

import akka.serialization.Serializer
import net.imadz.application.services.transactor.MoneyTransferSagaTransactor.{FromAccountParticipant, ToAccountParticipant}
import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.common.CommonTypes.iMadzError
import net.imadz.common.Id
import net.imadz.domain.values.Money
import net.imadz.infra.saga.SagaPhase.{CommitPhase, CompensatePhase, PreparePhase}
import net.imadz.infra.saga.SagaTransactionStep
import net.imadz.infra.saga.proto.saga_v2.SagaParticipantPO.Participant.{FromAccount, ToAccount}
import net.imadz.infra.saga.proto.saga_v2.{SagaParticipantPO, SagaTransactionStepPO, TransactionPhasePO}
import net.imadz.infrastructure.persistence.ParticipantAdapter
import net.imadz.infrastructure.proto.credits.MoneyPO
import net.imadz.infrastructure.proto.saga_participant.{FromAccountParticipantPO, ToAccountParticipantPO}

import java.util.Currency
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationLong
import scala.util.{Failure, Success, Try}

case class SagaTransactionStepSerializer(repository: CreditBalanceRepository, ec: ExecutionContext) extends Serializer with ParticipantAdapter {

  implicit val executionContext: ExecutionContext = ec

  override def identifier: Int = 1234

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case step: SagaTransactionStep[_, _] => serializeSagaTransactionStep(step).toByteArray
    case _ => throw new IllegalArgumentException(s"Cannot serialize object of type ${o.getClass}")
  }

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    Try(SagaTransactionStepPO.parseFrom(bytes)).map(deserializeSagaTransactionStep) match {
      case Success(step) => step
      case Failure(e) => throw new RuntimeException(s"Failed to deserialize SagaTransactionStep: ${e.getMessage}")
    }
  }

  override def includeManifest: Boolean = false

  def serializeSagaTransactionStep(step: SagaTransactionStep[_, _]): SagaTransactionStepPO = {
    val participantPO = step.participant match {
      case FromAccountParticipant(fromUserId, amount, _) =>
        FromAccount(FromAccountParticipantPO(fromUserId.toString, Some(MoneyPO(amount.amount.doubleValue, amount.currency.getCurrencyCode))))
      case ToAccountParticipant(toUserId, amount, _) =>
        ToAccount(ToAccountParticipantPO(toUserId.toString, Some(MoneyPO(amount.amount.doubleValue, amount.currency.getCurrencyCode))))
    }

    SagaTransactionStepPO(
      stepId = step.stepId,
      phase = step.phase match {
        case PreparePhase => TransactionPhasePO.PREPARE_PHASE
        case CommitPhase => TransactionPhasePO.COMMIT_PHASE
        case CompensatePhase => TransactionPhasePO.COMPENSATE_PHASE
      },
      participant = Some(SagaParticipantPO(participantPO)),
      maxRetries = step.maxRetries,
      timeoutDurationMillis = step.timeoutDuration.toMillis,
      retryWhenRecoveredOngoing = step.retryWhenRecoveredOngoing
    )
  }

  def deserializeSagaTransactionStep(stepPO: SagaTransactionStepPO): SagaTransactionStep[iMadzError, String] = {
    val participant = stepPO.participant match {
      case Some(SagaParticipantPO(FromAccount(
      FromAccountParticipantPO(fromUserId, Some(MoneyPO(amount, currencyCode, _)), _)), _)) =>
        FromAccountParticipant(Id.of(fromUserId), Money(BigDecimal(amount), Currency.getInstance(currencyCode)), repository)
      case Some(SagaParticipantPO(ToAccount(ToAccountParticipantPO(toUserId, Some(MoneyPO(value, currency, _)), _)), _)) =>
        ToAccountParticipant(Id.of(toUserId), Money(BigDecimal(value), Currency.getInstance(currency)), repository)
      case _ => throw new IllegalArgumentException("Invalid participant type in SagaTransactionStepPO")
    }

    SagaTransactionStep[iMadzError, String](
      stepId = stepPO.stepId,
      phase = stepPO.phase match {
        case TransactionPhasePO.PREPARE_PHASE => PreparePhase
        case TransactionPhasePO.COMMIT_PHASE => CommitPhase
        case TransactionPhasePO.COMPENSATE_PHASE => CompensatePhase
      },
      participant = participant,
      maxRetries = stepPO.maxRetries,
      timeoutDuration = stepPO.timeoutDurationMillis.millis,
      retryWhenRecoveredOngoing = stepPO.retryWhenRecoveredOngoing
    )
  }

}
