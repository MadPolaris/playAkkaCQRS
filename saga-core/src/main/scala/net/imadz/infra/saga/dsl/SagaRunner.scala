package net.imadz.infra.saga.dsl

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Scheduler}
import akka.actor.typed.scaladsl.AskPattern._
import akka.cluster.sharding.typed.scaladsl.EntityRef
import akka.util.Timeout
import net.imadz.infra.saga.SagaPhase.TransactionPhase
import net.imadz.infra.saga.SagaTransactionCoordinator
import net.imadz.infra.saga.SagaTransactionCoordinator._

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success}

class SagaStartRejectedException(val rejection: StartRejection, message: String) extends RuntimeException(message)

class SagaCompletionTimeoutException(transactionId: String)
    extends RuntimeException(s"Saga completion for transaction $transactionId timed out before the terminal result arrived")

/**
  * Multi-waiter completion registry: `run` and any number of concurrent admin operations
  * (proceed / resolveSuspended / retryPhase) may all await the same transaction's terminal
  * TransactionResult. The node-local bridge completes every registered waiter and drops them.
  */
private[dsl] final class CompletionRegistry {

  private val waiters = new ConcurrentHashMap[String, CopyOnWriteArrayList[Promise[TransactionResult]]]()

  def add(transactionId: String): Promise[TransactionResult] = {
    val promise = Promise[TransactionResult]()
    waiters
      .computeIfAbsent(transactionId, _ => new CopyOnWriteArrayList[Promise[TransactionResult]]())
      .add(promise)
    promise
  }

  def remove(transactionId: String, promise: Promise[TransactionResult]): Unit =
    Option(waiters.get(transactionId)).foreach { list =>
      list.remove(promise)
      if (list.isEmpty) waiters.remove(transactionId, list)
    }

  /** Terminal result arrived through the bridge: complete every waiter of the transaction. */
  def completeAll(transactionId: String, result: TransactionResult): Unit =
    Option(waiters.remove(transactionId)).foreach(_.asScala.foreach(_.trySuccess(result)))

  /** Complete only this caller's waiter (synthesized replies: AlreadyRunning / AlreadyFinished / start ack). */
  def succeed(transactionId: String, promise: Promise[TransactionResult], result: TransactionResult): Unit =
    if (promise.trySuccess(result)) remove(transactionId, promise)

  def fail(transactionId: String, promise: Promise[TransactionResult], cause: Throwable): Unit =
    if (promise.tryFailure(cause)) remove(transactionId, promise)

  def scheduleBackstop(transactionId: String, promise: Promise[TransactionResult], deadline: FiniteDuration, system: ActorSystem[_]): Unit =
    system.scheduler.scheduleOnce(deadline, () => fail(transactionId, promise, new SagaCompletionTimeoutException(transactionId)))(system.executionContext)
}

/**
  * One instance per saga definition per node. The coordinator is sharded per
  * transactionId, so `coordinatorRef` is a resolver, not a single EntityRef.
  *
  * Completion contract: `run` returns a Future completed by the transaction's terminal
  * TransactionResult (delivered through the node-local completion bridge), or failed by
  * the start rejection / the backstop timeout when the entity restarted mid-flight and
  * lost the in-memory reply channel. Callers needing durable status poll `statusOf`.
  */
final class SagaRunner[E, A](
    definition: SagaDefinition[E, _, A],
    coordinatorRef: String => EntityRef[SagaTransactionCoordinator.Command],
    system: ActorSystem[_],
    startAckTimeout: FiniteDuration = 15.seconds,
    completionBackstop: FiniteDuration = 5.minutes
) {
  private implicit val scheduler: Scheduler = system.scheduler
  private implicit val ec: ExecutionContext = system.executionContext
  private val spawnTimeout: Timeout = Timeout(10.seconds)
  private val instanceSeq = SagaRunner.instanceSeq.incrementAndGet()
  private val registry = new CompletionRegistry

  /** Node-local completion bridge: terminal TransactionResults from coordinator entities
    * (possibly on other nodes — path-based ActorRef resolves within the cluster) land here. */
  private val completionRef: ActorRef[TransactionResult] = {
    implicit val t: Timeout = spawnTimeout
    system.systemActorOf(
      Behaviors.receiveMessage[TransactionResult] { result =>
        registry.completeAll(result.snapshot.transactionId, result)
        Behaviors.same
      },
      s"saga-runner-completion-${definition.name}-v${definition.version}-$instanceSeq-${UUID.randomUUID()}"
    )
  }

  def run(
      transactionId: String,
      args: A,
      traceId: String = "",
      singleStep: Boolean = false
  ): Future[TransactionResult] = {
    val argsBytes = definition.argsCodec.encode(args)
    val promise = registry.add(transactionId)
    registry.scheduleBackstop(transactionId, promise, completionBackstop, system)

    implicit val timeout: Timeout = Timeout(startAckTimeout)
    val startAck = coordinatorRef(transactionId).ask { (replyTo: ActorRef[SagaStartReply]) =>
      StartSaga(transactionId, definition.name, definition.version, argsBytes, traceId, singleStep, Some(replyTo), Some(completionRef))
    }

    startAck.onComplete {
      case Success(Started) => () // terminal result arrives via completionRef
      case Success(AlreadyRunning(snapshot)) =>
        registry.succeed(transactionId, promise, TransactionResult(successful = false, snapshot, snapshot.failReason.getOrElse("already running")))
      case Success(AlreadyFinished(successful, failReason, steps)) =>
        registry.succeed(transactionId, promise, TransactionResult(successful, snapshotFrom(transactionId, successful, failReason, steps), failReason.getOrElse("")))
      case Success(rejection: StartRejection) =>
        registry.fail(transactionId, promise, new SagaStartRejectedException(rejection, s"Saga start rejected: $rejection"))
      case Failure(t) =>
        registry.fail(transactionId, promise, t)
    }
    promise.future
  }

  def statusOf(transactionId: String, statusTimeout: FiniteDuration = 15.seconds): Future[Option[StatusSnapshot]] = {
    implicit val timeout: Timeout = Timeout(statusTimeout)
    coordinatorRef(transactionId).ask { (ref: ActorRef[Option[StatusSnapshot]]) => GetTransactionStatus(transactionId, ref) }
  }

  def admin: SagaAdminOps = new SagaAdminOps(coordinatorRef, completionRef, registry, system)

  private def snapshotFrom(txId: String, successful: Boolean, failReason: Option[String], steps: List[StepSpecSnapshot]): StatusSnapshot =
    StatusSnapshot(
      transactionId = txId,
      definitionName = definition.name,
      definitionVersion = definition.version,
      traceId = "",
      status = if (successful) Completed.toString else Failed.toString,
      currentPhase = "",
      currentStepGroup = 0,
      isPaused = false,
      singleStep = false,
      failReason = failReason,
      steps = steps
    )
}

private object SagaRunner {
  val instanceSeq: AtomicLong = new AtomicLong()
}

/** Ops interventions routed through the runner so the completion channel stays attached.
  * The returned Future completes with the transaction's terminal result. */
final class SagaAdminOps private[dsl] (
    coordinatorRef: String => EntityRef[SagaTransactionCoordinator.Command],
    completionRef: ActorRef[TransactionResult],
    registry: CompletionRegistry,
    system: ActorSystem[_]
)(implicit scheduler: Scheduler) {

  def proceed(transactionId: String, timeout: FiniteDuration = 5.minutes): Future[TransactionResult] =
    send(transactionId, ProceedNext(Some(completionRef)), timeout)

  def resolveSuspended(transactionId: String, timeout: FiniteDuration = 5.minutes): Future[TransactionResult] =
    send(transactionId, ResolveSuspended(Some(completionRef)), timeout)

  def retryPhase(transactionId: String, timeout: FiniteDuration = 5.minutes): Future[TransactionResult] =
    send(transactionId, RetryCurrentPhase(Some(completionRef)), timeout)

  /** Fire-and-forget: the fixed step is treated as logically succeeded; a following
    * resolveSuspended re-drives the phase. */
  def fixStep(transactionId: String, stepId: String, phase: TransactionPhase): Unit =
    coordinatorRef(transactionId) ! ManualFixStep(stepId, phase, Some(completionRef))

  private def send(transactionId: String, command: Command, timeout: FiniteDuration): Future[TransactionResult] = {
    val promise = registry.add(transactionId)
    registry.scheduleBackstop(transactionId, promise, timeout, system)
    coordinatorRef(transactionId) ! command
    promise.future
  }
}
