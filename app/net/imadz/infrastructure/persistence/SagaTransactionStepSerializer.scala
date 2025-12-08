package net.imadz.infrastructure.persistence

import com.google.protobuf.ByteString
import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.application.services.transactor.MoneyTransferSagaTransactor.{FromAccountParticipant, ToAccountParticipant}
import net.imadz.common.CommonTypes.iMadzError
import net.imadz.common.Id
import net.imadz.domain.values.Money
import net.imadz.infra.saga.proto.saga_v2.{SagaParticipantPO, SagaTransactionStepPO}
import net.imadz.infra.saga.serialization.AbsSagaTransactionStepSerializer
import net.imadz.infra.saga.{SagaParticipant, SagaTransactionStep}
import net.imadz.infrastructure.proto.credits.MoneyPO
import net.imadz.infrastructure.proto.saga_participant.{FromAccountParticipantPO, ToAccountParticipantPO}

import java.util.Currency
import scala.concurrent.ExecutionContext

case class SagaTransactionStepSerializer(repository: CreditBalanceRepository, ec: ExecutionContext) extends AbsSagaTransactionStepSerializer {

  implicit val executionContext: ExecutionContext = ec

  override def identifier: Int = 1234

  override def serializeSagaTransactionStep(step: SagaTransactionStep[_, _]): SagaTransactionStepPO = {
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
    val genericParticipantPO = SagaParticipantPO(
      typeName = typeName,
      payload = payloadBytes
    )
    writeSagaParticipantPO(step, genericParticipantPO)
  }


  override def deserializeSagaTransactionStep(stepPO: SagaTransactionStepPO): SagaTransactionStep[iMadzError, String] = {
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

    readSagaTransactionStep(stepPO, participant)
  }

}
