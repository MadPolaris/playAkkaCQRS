package net.imadz.application.services.transactor

import akka.actor.typed.Scheduler
import net.imadz.application.aggregates.CreditBalanceProtocol._
import net.imadz.common.CommonTypes.{Id, iMadzError}
import net.imadz.common.Id
import net.imadz.domain.values.Money
import net.imadz.infra.saga.SagaParticipant.SagaResult
import net.imadz.infra.saga.dsl.{AskParticipant, ErrorAction, ErrorRules, PhaseAsk}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/** TCC participant of the paying side: reserve -> deduct -> release, all via the
  * sharded CreditBalance aggregate. Pure data — never journaled (saga_v3). */
object FromAccountParticipant {
  private val Rules: ErrorRules[iMadzError] = ErrorRules(
    business = { case iMadzError("60003", _) | iMadzError("60004", _) => ErrorAction.NonRetryable },
    describe = e => s"${e.code}: ${e.message}"
  )
}

case class FromAccountParticipant(fromUserId: Id, amount: Money)(implicit ec: ExecutionContext, scheduler: Scheduler)
    extends AskParticipant[iMadzError, String, AppSagaContext](
      rules = FromAccountParticipant.Rules,
      askTimeout = 5.seconds
    ) {

  override protected val prepareBinding: Option[PhaseAsk[iMadzError, String, AppSagaContext]] =
    Some(PhaseAsk.ask[ReserveFunds, FundsReservationConfirmation, iMadzError, String, AppSagaContext](
      ref = ctx => ctx.creditBalances.findCreditBalanceByUserId(fromUserId),
      command = (txId, replyTo) => ReserveFunds(Id.of(txId), amount, replyTo),
      mapReply = r => r.error.map(Left(_)).getOrElse(Right(SagaResult(r.transferId.toString)))
    ))

  override protected val commitBinding: Option[PhaseAsk[iMadzError, String, AppSagaContext]] =
    Some(PhaseAsk.ask[DeductFunds, FundsDeductionConfirmation, iMadzError, String, AppSagaContext](
      ref = ctx => ctx.creditBalances.findCreditBalanceByUserId(fromUserId),
      command = (txId, replyTo) => DeductFunds(Id.of(txId), replyTo),
      mapReply = r => r.error.map(Left(_)).getOrElse(Right(SagaResult(r.transferId.toString)))
    ))

  override protected val compensateBinding: Option[PhaseAsk[iMadzError, String, AppSagaContext]] =
    Some(PhaseAsk.ask[ReleaseReservedFunds, FundsReleaseConfirmation, iMadzError, String, AppSagaContext](
      ref = ctx => ctx.creditBalances.findCreditBalanceByUserId(fromUserId),
      command = (txId, replyTo) => ReleaseReservedFunds(Id.of(txId), replyTo),
      mapReply = r => r.error.map(Left(_)).getOrElse(Right(SagaResult(r.transferId.toString)))
    ))
}
