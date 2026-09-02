package net.imadz.infra.saga.acceptance

import akka.actor.typed.{ActorRef, Scheduler}
import net.imadz.infra.saga.SagaParticipant
import net.imadz.infra.saga.SagaParticipant.{SagaResult, _}
import net.imadz.infra.saga.SagaPhase.TransactionPhase
import net.imadz.infra.saga.dsl.{AskParticipant, ErrorRules, PhaseAsk, PhaseAwareParticipant, SagaDefinition, SagaStep}
import play.api.libs.json.Format

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future

/** Shared fixtures for saga-core acceptance and migrated specs. */
object TestSagaSupport {

  final case class TransferArgs(from: String, to: String, amount: Int)
  object TransferArgs {
    implicit val format: Format[TransferArgs] = play.api.libs.json.Json.format[TransferArgs]
  }

  /** Outcome script for one phase of [[CountingParticipant]]. */
  sealed trait Script
  object Script {
    case object Ok extends Script
    case class BusinessError(code: String) extends Script   // Left(E) — business track
    case class RetryableFailure(msg: String) extends Script // thrown track: RetryableFailure exception
    case object Hang extends Script                          // Future.never — drives timeouts/recovery
  }

  /** AskParticipant with per-phase scripts and invocation counters. */
  class CountingParticipant(val participantName: String)(implicit ec: scala.concurrent.ExecutionContext, scheduler: Scheduler)
      extends AskParticipant[String, String, Any](ErrorRules.none) {

    val prepareCalls = new AtomicInteger(0)
    val commitCalls = new AtomicInteger(0)
    val compensateCalls = new AtomicInteger(0)

    @volatile var prepareScript: Script = Script.Ok
    @volatile var commitScript: Script = Script.Ok
    @volatile var compensateScript: Script = Script.Ok

    private def run(script: Script, counter: AtomicInteger, phase: String): Future[Either[String, SagaResult[String]]] = {
      counter.incrementAndGet()
      script match {
        case Script.Ok                     => Future.successful(Right(SagaResult(s"$phase-ok")))
        case Script.BusinessError(code)    => Future.successful(Left(code))
        case Script.RetryableFailure(msg)  => Future.failed(RetryableFailure(msg))
        case Script.Hang                   => Future.never
      }
    }

    override val prepareBinding: Option[PhaseAsk[String, String, Any]] =
      Some(PhaseAsk.direct((txId, _, _) => run(prepareScript, prepareCalls, "prepare")))
    override val commitBinding: Option[PhaseAsk[String, String, Any]] =
      Some(PhaseAsk.direct((txId, _, _) => run(commitScript, commitCalls, "commit")))
    override val compensateBinding: Option[PhaseAsk[String, String, Any]] =
      Some(PhaseAsk.direct((txId, _, _) => run(compensateScript, compensateCalls, "compensate")))
  }

  /** Plain SagaParticipant (escape hatch): expands to all three phases. */
  case object AlwaysOkRawParticipant extends SagaParticipant[String, String, Any] {
    override def doPrepare(transactionId: String, context: Any, traceId: String) = Future.successful(Right(SagaResult("prepared")))
    override def doCommit(transactionId: String, context: Any, traceId: String) = Future.successful(Right(SagaResult("committed")))
    override def doCompensate(transactionId: String, context: Any, traceId: String) = Future.successful(Right(SagaResult("compensated")))
    override protected def customClassification: PartialFunction[Throwable, RetryableOrNotException] = PartialFunction.empty
  }

  /** Two-phase-aware participant: prepare+commit only (no compensate binding). */
  final class PrepareCommitOnlyParticipant(name: String)(implicit ec: scala.concurrent.ExecutionContext, scheduler: Scheduler)
      extends AskParticipant[String, String, Any]() with PhaseAwareParticipant[String, String, Any] {
    override val prepareBinding: Option[PhaseAsk[String, String, Any]] = Some(PhaseAsk.direct((_, _, _) => Future.successful(Right(SagaResult("p")))))
    override val commitBinding: Option[PhaseAsk[String, String, Any]] = Some(PhaseAsk.direct((_, _, _) => Future.successful(Right(SagaResult("c")))))
    override def boundPhases: Set[TransactionPhase] = Set(net.imadz.infra.saga.SagaPhase.PreparePhase, net.imadz.infra.saga.SagaPhase.CommitPhase)
  }

  def logicalStep(stepId: String, participant: SagaParticipant[String, String, Any], maxRetries: Int = 3, group: Int = 1): SagaStep[String, String, Any] =
    SagaStep(stepId, participant, net.imadz.infra.saga.dsl.ResiliencePolicy(maxRetries = maxRetries), group)

  /** Builds and registers a definition; args type is fixed for tests. */
  def registerDefinition(
      name: String,
      version: Int = 1,
      defaultResilience: net.imadz.infra.saga.dsl.ResiliencePolicy = net.imadz.infra.saga.dsl.ResiliencePolicy.defaults,
      steps: Seq[SagaStep[String, String, Any]]
  ): SagaDefinition[String, Any, TransferArgs] = {
    val definition = SagaDefinition[String, Any, TransferArgs](
      name = name,
      version = version,
      argsCodec = net.imadz.infra.saga.dsl.ArgsCodec.playJson[TransferArgs],
      steps = _ => steps,
      defaultResilience = defaultResilience
    )
    net.imadz.infra.saga.dsl.SagaRegistry.register(definition)
    definition
  }
}
