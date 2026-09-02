package net.imadz.application.services.transactor

import net.imadz.infra.saga.SagaParticipant.SagaResult
import net.imadz.infra.saga.dsl.{AskParticipant, ErrorAction, ErrorRules, PhaseAsk}

import java.util.concurrent.{ConcurrentHashMap, Executors, TimeUnit}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
/** Fault-injection showcase participant. The behavior script is global (set via the ops
  * endpoint per stepId); the attempt counters live on the participant instance, which is
  * rebuilt per transaction — no cross-transaction counter leak. */
object ShowcaseParticipant {
  private val Rules: ErrorRules[String] = ErrorRules[String](
    thrown = ({ case e: Throwable if e.getMessage != null && e.getMessage.contains("RetryableFailure") => ErrorAction.Retryable }: PartialFunction[Throwable, ErrorAction])
  )

  sealed trait Behavior
  case object Success extends Behavior
  case object FailRetryable extends Behavior
  case object FailNonRetryable extends Behavior
  case object Timeout extends Behavior
  case object FailTwiceThenSucceed extends Behavior

  private val behaviors = new ConcurrentHashMap[String, Behavior]()

  def setBehavior(stepId: String, behavior: Behavior): Unit = behaviors.put(stepId, behavior)

  def getBehavior(stepId: String): Behavior = Option(behaviors.get(stepId)).getOrElse(Success)

  private val scheduler = Executors.newScheduledThreadPool(1)

  def delay[T](duration: FiniteDuration)(block: => T): Future[T] = {
    val promise = Promise[T]()
    scheduler.schedule(new Runnable {
      override def run(): Unit = promise.success(block)
    }, duration.toMillis, TimeUnit.MILLISECONDS)
    promise.future
  }
}

class ShowcaseParticipant(val participantId: String)(implicit ec: ExecutionContext, scheduler: akka.actor.typed.Scheduler)
    extends AskParticipant[String, String, Any](
      rules = ShowcaseParticipant.Rules,
      askTimeout = 15.seconds
    ) {

  import ShowcaseParticipant._

  /** Per-transaction attempt counters — die with this participant instance. */
  private val attempts = new ConcurrentHashMap[String, Int]()

  private def binding(phase: String): Option[PhaseAsk[String, String, Any]] =
    Some(PhaseAsk.direct[String, String, Any]((_, _, _) => execute(phase)))

  override protected val prepareBinding: Option[PhaseAsk[String, String, Any]] = binding("prepare")
  override protected val commitBinding: Option[PhaseAsk[String, String, Any]] = binding("commit")
  override protected val compensateBinding: Option[PhaseAsk[String, String, Any]] = binding("compensate")

  private def execute(phase: String): Future[Either[String, SagaResult[String]]] = {
    val behavior = getBehavior(participantId)
    val currentAttempt = attempts.compute(s"$participantId-$phase", (_, v) => if (v == null) 1 else v + 1)

    val randomDelay = (1000 + scala.util.Random.nextInt(1500)).milliseconds

    behavior match {
      case Success =>
        delay(randomDelay)(Right(SagaResult(s"$participantId-$phase-success")))

      case FailRetryable | FailTwiceThenSucceed =>
        if (currentAttempt <= 2)
          delay(randomDelay)(()).flatMap(_ => Future.failed(new RuntimeException(s"RetryableFailure: simulated transient error (attempt $currentAttempt)")))
        else
          delay(randomDelay)(Right(SagaResult(s"$participantId-$phase-healed")))

      case FailNonRetryable =>
        delay(randomDelay)(()).flatMap(_ => Future.failed(new RuntimeException("NonRetryable: manual non-retryable error")))

      case Timeout =>
        delay(10.seconds)(Right(SagaResult("timeout simulated")))
    }
  }
}
