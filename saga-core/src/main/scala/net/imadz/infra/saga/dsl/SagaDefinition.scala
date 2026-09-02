package net.imadz.infra.saga.dsl

import net.imadz.infra.saga.SagaParticipant
import net.imadz.infra.saga.SagaPhase
import net.imadz.infra.saga.SagaPhase.PreparePhase
import net.imadz.infra.saga.SagaTransactionCoordinator.TransactionResult
import net.imadz.infra.saga.SagaTransactionStep

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import scala.util.{Failure, Success, Try}

/** Marker: business event emitted through SagaDefinition.onResult. */
trait SagaBusinessEvent

/** Participants implementing this declare which phases they actually have bindings for. */
trait PhaseAwareParticipant[E, R, C] { self: SagaParticipant[E, R, C] =>
  def boundPhases: Set[SagaPhase.TransactionPhase]
}

/** One logical TCC step: a participant plus its resilience. Expanded into up to
  * three engine steps (one per bound phase) by SagaDefinition.expand. */
final case class SagaStep[E, R, C](
    stepId: String,
    participant: SagaParticipant[E, R, C],
    resilience: ResiliencePolicy = ResiliencePolicy.defaults,
    stepGroup: Int = 1
)

/**
 * The declarative saga definition — the single artifact a business developer writes.
 * `steps` must be a pure function of (definition, args): participants are deterministic
 * products of the two, which is exactly why the journal never stores participant bytes.
 */
final class SagaDefinition[E, C, A] private (
    val name: String,
    val version: Int,
    val argsCodec: ArgsCodec[A],
    val steps: A => Seq[SagaStep[E, _, C]],
    val preCheck: A => Either[E, A],
    val errorText: E => (String, String),
    val onResult: (A, TransactionResult) => Seq[SagaBusinessEvent],
    val defaultResilience: ResiliencePolicy
) {

  private val allPhases: Set[SagaPhase.TransactionPhase] =
    Set(PreparePhase, SagaPhase.CommitPhase, SagaPhase.CompensatePhase)

  /** Expands logical steps into engine steps: one per bound phase, resilience applied,
    * result types erased to Any (the engine is result-type-agnostic end to end). */
  def expand(args: A): Try[List[SagaTransactionStep[E, Any, C]]] = Try {
    steps(args).toList.flatMap { logical =>
      val bound = logical.participant match {
        case aware: PhaseAwareParticipant[_, _, _] =>
          val awareAny = aware.asInstanceOf[PhaseAwareParticipant[E, _, C]]
          allPhases.intersect(awareAny.boundPhases)
        case _ => allPhases
      }
      val r = if (logical.resilience == ResiliencePolicy.defaults) defaultResilience else logical.resilience
      bound.toList.sortBy(phaseRank).map { phase =>
        SagaTransactionStep[E, Any, C](
          stepId = logical.stepId,
          phase = phase,
          participant = logical.participant.asInstanceOf[SagaParticipant[E, Any, C]],
          maxRetries = r.maxRetries,
          timeoutDuration = r.timeoutPerAttempt,
          retryWhenRecoveredOngoing = r.recovery == RecoveryBehavior.RetryIfOngoing,
          stepGroup = logical.stepGroup,
          circuitBreaker = r.circuitBreaker
        )
      }
    }
  }

  private def phaseRank(p: SagaPhase.TransactionPhase): Int = p match {
    case PreparePhase    => 0
    case SagaPhase.CommitPhase     => 1
    case SagaPhase.CompensatePhase => 2
  }
}

object SagaDefinition {
  def apply[E, C, A](
      name: String,
      version: Int,
      argsCodec: ArgsCodec[A],
      steps: A => Seq[SagaStep[E, _, C]],
      preCheck: A => Either[E, A] = (a: A) => Right(a),
      errorText: E => (String, String) = (e: E) => ("PRECHECK_FAILED", String.valueOf(e)),
      onResult: (A, TransactionResult) => Seq[SagaBusinessEvent] = (_: A, _: TransactionResult) => Nil,
      defaultResilience: ResiliencePolicy = ResiliencePolicy.defaults
  ): SagaDefinition[E, C, A] =
    new SagaDefinition[E, C, A](name, version, argsCodec, steps, preCheck, errorText, onResult, defaultResilience)
}

trait ArgsCodec[A] {
  def encode(a: A): Array[Byte]
  def decode(bytes: Array[Byte]): Try[A]
}

object ArgsCodec {
  def playJson[A](implicit fmt: play.api.libs.json.Format[A]): ArgsCodec[A] = new ArgsCodec[A] {
    private val utf8 = StandardCharsets.UTF_8
    override def encode(a: A): Array[Byte] = play.api.libs.json.Json.toJson(a).toString.getBytes(utf8)
    override def decode(bytes: Array[Byte]): Try[A] =
      Try(new String(bytes, utf8)).flatMap(raw => Try(play.api.libs.json.Json.parse(raw))).map(_.as[A])
  }

  def fromFunctions[A](encode: A => Array[Byte], decodeFn: Array[Byte] => Try[A]): ArgsCodec[A] =
    new ArgsCodec[A] {
      override def encode(a: A): Array[Byte] = encode(a)
      override def decode(bytes: Array[Byte]): Try[A] = decodeFn(bytes)
    }
}

/** Definitions are registered at node startup, before cluster sharding recovers any
  * entity. Re-registering the same (name, version) overwrites — the self-healing path
  * for suspended transactions after a deployment fix. */
object SagaRegistry {
  private val definitions = new ConcurrentHashMap[(String, Int), SagaDefinition[_, _, _]]()

  def register[E, C, A](definition: SagaDefinition[E, C, A]): Unit = {
    definitions.put((definition.name, definition.version), definition)
    ()
  }

  def resolve(name: String, version: Int): Try[SagaDefinition[_, _, _]] =
    Option(definitions.get((name, version)))
      .map(Success(_))
      .getOrElse(Failure(new UnknownSagaDefinitionException(s"Saga definition not registered: $name:v$version")))

  def registeredCount: Int = definitions.size()
}

class UnknownSagaDefinitionException(message: String) extends RuntimeException(message)

/** Stable content hash of encoded args — the idempotency key discriminator. */
object ArgsHash {
  def sha256(argsBytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(argsBytes).map(b => f"$b%02x").mkString
}
