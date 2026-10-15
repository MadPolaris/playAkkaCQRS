package net.imadz.infra.saga.dsl

import akka.actor.typed.{ActorRef, Scheduler}
import akka.actor.typed.RecipientRef
import net.imadz.infra.saga.SagaParticipant
import net.imadz.infra.saga.SagaParticipant.SagaResult

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

/** Fluent builder for creating declarative [[SagaStep]] instances with inline ask bindings,
  * eliminating the need to write boilerplate [[AskParticipant]] subclasses.
  *
  * {{{
  * val step = SagaStep.builder[iMadzError, AppSagaContext]("transfer-out")
  *   .inGroup(1)
  *   .withAsk[ReserveFunds, FundsReservationConfirmation](
  *     ref = ctx => ctx.creditBalances.findCreditBalanceByUserId(userId),
  *     cmd = (txId, replyTo) => ReserveFunds(Id.of(txId), amount, replyTo),
  *     map = r => r.error.map(Left(_)).getOrElse(Right(SagaResult(r.transferId.toString)))
  *   )
  *   .commitAsk[DeductFunds, FundsDeductionConfirmation](
  *     ref = ctx => ctx.creditBalances.findCreditBalanceByUserId(userId),
  *     cmd = (txId, replyTo) => DeductFunds(Id.of(txId), replyTo),
  *     map = r => r.error.map(Left(_)).getOrElse(Right(SagaResult(r.transferId.toString)))
  *   )
  *   .compensateAsk[ReleaseReservedFunds, FundsReleaseConfirmation](
  *     ref = ctx => ctx.creditBalances.findCreditBalanceByUserId(userId),
  *     cmd = (txId, replyTo) => ReleaseReservedFunds(Id.of(txId), replyTo),
  *     map = r => r.error.map(Left(_)).getOrElse(Right(SagaResult(r.transferId.toString)))
  *   )
  *   .withResilience(ResiliencePolicy(maxRetries = 5))
  *   .build()
  * }}}
  */
final class SagaStepBuilder[E, C] private (
    private val stepId: String,
    private val group: Int,
    private val resilience: ResiliencePolicy,
    private val errorRules: ErrorRules[E],
    private val timeout: FiniteDuration,
    private val prepareAskOpt: Option[PhaseAsk[E, String, C]],
    private val commitAskOpt: Option[PhaseAsk[E, String, C]],
    private val compensateAskOpt: Option[PhaseAsk[E, String, C]]
) {

  def inGroup(g: Int): SagaStepBuilder[E, C] =
    copy(group = g)

  def withResilience(r: ResiliencePolicy): SagaStepBuilder[E, C] =
    copy(resilience = r)

  def withTimeout(t: FiniteDuration): SagaStepBuilder[E, C] =
    copy(timeout = t)

  def withErrorRules(rules: ErrorRules[E]): SagaStepBuilder[E, C] =
    copy(errorRules = rules)

  def prepareAsk[Cmd, Reply](
      ref: C => RecipientRef[Cmd],
      cmd: (String, ActorRef[Reply]) => Cmd,
      map: Reply => Either[E, SagaResult[String]]
  ): SagaStepBuilder[E, C] =
    copy(prepareAskOpt = Some(PhaseAsk.ask(ref, cmd, map)))

  def commitAsk[Cmd, Reply](
      ref: C => RecipientRef[Cmd],
      cmd: (String, ActorRef[Reply]) => Cmd,
      map: Reply => Either[E, SagaResult[String]]
  ): SagaStepBuilder[E, C] =
    copy(commitAskOpt = Some(PhaseAsk.ask(ref, cmd, map)))

  def compensateAsk[Cmd, Reply](
      ref: C => RecipientRef[Cmd],
      cmd: (String, ActorRef[Reply]) => Cmd,
      map: Reply => Either[E, SagaResult[String]]
  ): SagaStepBuilder[E, C] =
    copy(compensateAskOpt = Some(PhaseAsk.ask(ref, cmd, map)))

  def prepareBinding(ask: PhaseAsk[E, String, C]): SagaStepBuilder[E, C] =
    copy(prepareAskOpt = Some(ask))

  def commitBinding(ask: PhaseAsk[E, String, C]): SagaStepBuilder[E, C] =
    copy(commitAskOpt = Some(ask))

  def compensateBinding(ask: PhaseAsk[E, String, C]): SagaStepBuilder[E, C] =
    copy(compensateAskOpt = Some(ask))

  def build()(implicit ec: ExecutionContext, scheduler: Scheduler): SagaStep[E, String, C] = {
    val prep = prepareAskOpt
    val comm = commitAskOpt
    val comp = compensateAskOpt
    val participant = new AskParticipant[E, String, C](errorRules, timeout)(ec, scheduler) {
      override protected val prepareBinding: Option[PhaseAsk[E, String, C]] = prep
      override protected val commitBinding: Option[PhaseAsk[E, String, C]] = comm
      override protected val compensateBinding: Option[PhaseAsk[E, String, C]] = comp
    }
    SagaStep(stepId, participant, resilience, group)
  }

  private def copy(
      stepId: String = this.stepId,
      group: Int = this.group,
      resilience: ResiliencePolicy = this.resilience,
      errorRules: ErrorRules[E] = this.errorRules,
      timeout: FiniteDuration = this.timeout,
      prepareAskOpt: Option[PhaseAsk[E, String, C]] = this.prepareAskOpt,
      commitAskOpt: Option[PhaseAsk[E, String, C]] = this.commitAskOpt,
      compensateAskOpt: Option[PhaseAsk[E, String, C]] = this.compensateAskOpt
  ): SagaStepBuilder[E, C] =
    new SagaStepBuilder(
      stepId, group, resilience, errorRules, timeout,
      prepareAskOpt, commitAskOpt, compensateAskOpt
    )
}

object SagaStepBuilder {
  def of[E, C](stepId: String): SagaStepBuilder[E, C] =
    new SagaStepBuilder[E, C](
      stepId = stepId,
      group = 1,
      resilience = ResiliencePolicy.defaults,
      errorRules = ErrorRules.none[E],
      timeout = 5.seconds,
      prepareAskOpt = None,
      commitAskOpt = None,
      compensateAskOpt = None
    )
}
