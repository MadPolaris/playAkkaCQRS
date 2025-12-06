package net.imadz.infra.saga.serialization

import akka.serialization.Serializer
import com.google.protobuf.ByteString
import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.application.services.transactor.MoneyTransferSagaTransactor.{FromAccountParticipant, ToAccountParticipant}
import net.imadz.common.CommonTypes.iMadzError
import net.imadz.common.Id
import net.imadz.domain.values.Money
import net.imadz.infra.saga.SagaPhase.{CommitPhase, CompensatePhase, PreparePhase}
import net.imadz.infra.saga.proto.saga_v2.{SagaParticipantPO, SagaTransactionStepPO, TransactionPhasePO}
import net.imadz.infra.saga.{SagaParticipant, SagaTransactionStep}
import net.imadz.infrastructure.proto.credits.MoneyPO
import net.imadz.infrastructure.proto.saga_participant.{FromAccountParticipantPO, ToAccountParticipantPO}

import java.util.Currency
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationLong
import scala.util.{Failure, Success, Try}

case class SagaTransactionStepSerializer(repository: CreditBalanceRepository, ec: ExecutionContext) extends Serializer  {

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
    val (typeName, payloadBytes) = step.participant match {
      case FromAccountParticipant(fromUserId, amount, _) =>
        val specificPO = FromAccountParticipantPO(
          fromUserId.toString,
          Some(MoneyPO(amount.amount.doubleValue, amount.currency.getCurrencyCode))
        )
        // 返回：(类型标记, 二进制数据)
        ("FromAccountParticipantPO", ByteString.copyFrom(specificPO.toByteArray))

      case ToAccountParticipant(toUserId, amount, _) =>
        val specificPO = ToAccountParticipantPO(
          toUserId.toString,
          Some(MoneyPO(amount.amount.doubleValue, amount.currency.getCurrencyCode))
        )
        ("ToAccountParticipantPO", ByteString.copyFrom(specificPO.toByteArray))

      case _ => throw new IllegalArgumentException("Unknown participant type")
    }

    // 3. 构建通用的 SagaParticipantPO
    val genericParticipantPO = SagaParticipantPO(
      typeName = typeName,
      payload = payloadBytes
    )

    SagaTransactionStepPO(
      stepId = step.stepId,
      phase = step.phase match {
        case PreparePhase => TransactionPhasePO.PREPARE_PHASE
        case CommitPhase => TransactionPhasePO.COMMIT_PHASE
        case CompensatePhase => TransactionPhasePO.COMPENSATE_PHASE
      },
      participant = Some(genericParticipantPO),
      maxRetries = step.maxRetries,
      timeoutDurationMillis = step.timeoutDuration.toMillis,
      retryWhenRecoveredOngoing = step.retryWhenRecoveredOngoing
    )
  }

  def deserializeSagaTransactionStep(stepPO: SagaTransactionStepPO): SagaTransactionStep[iMadzError, String] = {
    val genericParticipant = stepPO.participant.getOrElse(throw new IllegalArgumentException("Missing participant"))

    // 1. 根据 type_name 决定如何解析 payload
    val participant: SagaParticipant[iMadzError, String] = genericParticipant.typeName match {
      case "FromAccountParticipantPO" =>
        // 解析具体的业务 Proto
        val specificPO = FromAccountParticipantPO.parseFrom(genericParticipant.payload.toByteArray)
        // 转换回 Scala 对象
        FromAccountParticipant(Id.of(specificPO.fromUserId), Money(BigDecimal(specificPO.getAmount.amount), Currency.getInstance(specificPO.getAmount.currency)), repository)(ec)

      case "ToAccountParticipantPO" =>
        val specificPO = ToAccountParticipantPO.parseFrom(genericParticipant.payload.toByteArray)
        ToAccountParticipant(Id.of(specificPO.toUserId), Money(BigDecimal(specificPO.getAmount.amount), Currency.getInstance(specificPO.getAmount.currency)), repository)(ec)

      case _ => throw new IllegalArgumentException(s"Unknown type: ${genericParticipant.typeName}")
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
