package net.imadz.infra.saga.dsl

import akka.actor.typed.{ActorRef, RecipientRef, Scheduler}
import net.imadz.infra.saga.SagaParticipant.SagaResult
import net.imadz.infra.saga.SagaPhase

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

/** Fluent builder for creating declarative [[SagaStep]] instances with inline ask bindings,
  * eliminating the need to write boilerplate [[AskParticipant]] subclasses.
  *
  * Supports both:
  *  1. The 3-API chained phase DSL (`.prepare.ref(...).command[Reply](...).mapReply(...)`), where
  *     Scala infers `Cmd` automatically from `ref` and binds `(ref, command, mapReply)` step by step;
  *  2. The single-call `.prepareAsk[Cmd, Reply](ref, cmd, map)` / `.prepareDirect(fn)` methods.
  *
  * {{{
  * val step = SagaStep.builder[iMadzError, AppSagaContext]("transfer-out")
  *   .inGroup(1)
  *   .withResilience(ResiliencePolicy(maxRetries = 5))
  *   .prepare
  *     .ref(ctx => ctx.creditBalances.findCreditBalanceByUserId(userId))
  *     .command[FundsReservationConfirmation]((txId, replyTo) => ReserveFunds(Id.of(txId), amount, replyTo))
  *     .mapReply(r => r.error.map(Left(_)).getOrElse(Right(SagaResult(r.transferId.toString))))
  *   .commit
  *     .ref(ctx => ctx.creditBalances.findCreditBalanceByUserId(userId))
  *     .command[FundsDeductionConfirmation]((txId, replyTo) => DeductFunds(Id.of(txId), replyTo))
  *     .mapReply(r => r.error.map(Left(_)).getOrElse(Right(SagaResult(r.transferId.toString))))
  *   .compensate
  *     .ref(ctx => ctx.creditBalances.findCreditBalanceByUserId(userId))
  *     .command[FundsReleaseConfirmation]((txId, replyTo) => ReleaseReservedFunds(Id.of(txId), replyTo))
  *     .mapReply(r => r.error.map(Left(_)).getOrElse(Right(SagaResult(r.transferId.toString))))
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

  /** Start a 3-API chained binding (`.ref(...)` -> `.command(...)` -> `.mapReply(...)`) for the Prepare (Try) phase. */
  def prepare: SagaStepBuilder.PhaseRefStepBuilder[E, C] =
    new SagaStepBuilder.PhaseRefStepBuilder[E, C](ask => prepareBinding(ask))

  /** Start a 3-API chained binding (`.ref(...)` -> `.command(...)` -> `.mapReply(...)`) for the Commit (Confirm) phase. */
  def commit: SagaStepBuilder.PhaseRefStepBuilder[E, C] =
    new SagaStepBuilder.PhaseRefStepBuilder[E, C](ask => commitBinding(ask))

  /** Start a 3-API chained binding (`.ref(...)` -> `.command(...)` -> `.mapReply(...)`) for the Compensate (Cancel) phase. */
  def compensate: SagaStepBuilder.PhaseRefStepBuilder[E, C] =
    new SagaStepBuilder.PhaseRefStepBuilder[E, C](ask => compensateBinding(ask))

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

  def prepareDirect(f: (String, C, String) => Future[Either[E, SagaResult[String]]]): SagaStepBuilder[E, C] =
    copy(prepareAskOpt = Some(PhaseAsk.direct(f)))

  def commitDirect(f: (String, C, String) => Future[Either[E, SagaResult[String]]]): SagaStepBuilder[E, C] =
    copy(commitAskOpt = Some(PhaseAsk.direct(f)))

  def compensateDirect(f: (String, C, String) => Future[Either[E, SagaResult[String]]]): SagaStepBuilder[E, C] =
    copy(compensateAskOpt = Some(PhaseAsk.direct(f)))

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
    val participant = new AskParticipant[E, String, C](errorRules, timeout)(ec, scheduler)
      with PhaseAwareParticipant[E, String, C] {
      override protected val prepareBinding: Option[PhaseAsk[E, String, C]] = prep
      override protected val commitBinding: Option[PhaseAsk[E, String, C]] = comm
      override protected val compensateBinding: Option[PhaseAsk[E, String, C]] = comp
      override val boundPhases: Set[SagaPhase.TransactionPhase] = {
        val phases: Set[SagaPhase.TransactionPhase] = Set[Option[SagaPhase.TransactionPhase]](
          prep.map(_ => SagaPhase.PreparePhase: SagaPhase.TransactionPhase),
          comm.map(_ => SagaPhase.CommitPhase: SagaPhase.TransactionPhase),
          comp.map(_ => SagaPhase.CompensatePhase: SagaPhase.TransactionPhase)
        ).flatten
        if (phases.nonEmpty) phases
        else Set[SagaPhase.TransactionPhase](
          SagaPhase.PreparePhase,
          SagaPhase.CommitPhase,
          SagaPhase.CompensatePhase
        )
      }
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

  /** Step 1 of the 3-API chained phase binding: `.ref(...)` (or `.direct(...)`). */
  final class PhaseRefStepBuilder[E, C] private[dsl] (
      private val attach: PhaseAsk[E, String, C] => SagaStepBuilder[E, C]
  ) {
    /** API 1/3: Resolve the target ActorRef / EntityRef from context `C`. Infers `Cmd` automatically. */
    def ref[Cmd](resolveRef: C => RecipientRef[Cmd]): PhaseCommandStepBuilder[E, C, Cmd] =
      new PhaseCommandStepBuilder[E, C, Cmd](resolveRef, attach)

    /** Alternative: bind a direct async function for non-Actor interactions. */
    def direct(f: (String, C, String) => Future[Either[E, SagaResult[String]]]): SagaStepBuilder[E, C] =
      attach(PhaseAsk.direct(f))
  }

  /** Step 2 of the 3-API chained phase binding: `.command(...)`. */
  final class PhaseCommandStepBuilder[E, C, Cmd] private[dsl] (
      private val resolveRef: C => RecipientRef[Cmd],
      private val attach: PhaseAsk[E, String, C] => SagaStepBuilder[E, C]
  ) {
    /** API 2/3: Build the command message from `(transactionId, replyTo)`. */
    def command[Reply](buildCmd: (String, ActorRef[Reply]) => Cmd): PhaseMapReplyStepBuilder[E, C, Cmd, Reply] =
      new PhaseMapReplyStepBuilder[E, C, Cmd, Reply](resolveRef, buildCmd, attach)
  }

  /** Step 3 of the 3-API chained phase binding: `.mapReply(...)`. */
  final class PhaseMapReplyStepBuilder[E, C, Cmd, Reply] private[dsl] (
      private val resolveRef: C => RecipientRef[Cmd],
      private val buildCmd: (String, ActorRef[Reply]) => Cmd,
      private val attach: PhaseAsk[E, String, C] => SagaStepBuilder[E, C]
  ) {
    /** API 3/3: Map the Actor `Reply` into `Either[E, SagaResult[String]]` and return to [[SagaStepBuilder]]. */
    def mapReply(f: Reply => Either[E, SagaResult[String]]): SagaStepBuilder[E, C] =
      attach(PhaseAsk.ask[Cmd, Reply, E, String, C](resolveRef, buildCmd, f))
  }

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
