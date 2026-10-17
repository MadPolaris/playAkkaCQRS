package net.imadz.infra.saga.dsl

import akka.actor.typed.{ActorRef, Scheduler}
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.RecipientRef
import akka.util.Timeout
import net.imadz.infra.saga.SagaParticipant.SagaResult

import scala.concurrent.{ExecutionContext, Future}

/**
 * One interaction binding for a transaction phase. Pure data — every implicit
 * (execution context, scheduler, ask timeout) is supplied by the owning
 * AskParticipant at send time, so bindings can be declared in participant
 * constructors without implicit plumbing.
 */
sealed trait PhaseAsk[E, R, C] {
  def send(transactionId: String, ctx: C, traceId: String)(implicit ec: ExecutionContext, s: Scheduler, timeout: Timeout): Future[Either[E, SagaResult[R]]]
}

object PhaseAsk {

  /** Aggregate ask interaction: resolve the EntityRef from context, build the command
    * from (transactionId, replyTo) and map the reply into Either[E, SagaResult[R]].
    * RecipientRef covers sharding EntityRef as well as plain ActorRef (tests). */
  def ask[Cmd, Reply, E, R, C](
      ref: C => RecipientRef[Cmd],
      command: (String, ActorRef[Reply]) => Cmd,
      mapReply: Reply => Either[E, SagaResult[R]]
  ): PhaseAsk[E, R, C] = AskBinding(ref, command, mapReply)

  /** Step 1 of 3-API fluent builder for PhaseAsk: `PhaseAsk.ref(ctx => ...).command[Reply](...).mapReply(...)`. */
  def ref[C, Cmd](resolveRef: C => RecipientRef[Cmd]): PhaseAskCommandBuilder[C, Cmd] =
    new PhaseAskCommandBuilder[C, Cmd](resolveRef)

  /** Direct function for non-aggregate interactions (DB writes, HTTP calls, ...). */
  def direct[E, R, C](f: (String, C, String) => Future[Either[E, SagaResult[R]]]): PhaseAsk[E, R, C] = DirectBinding(f)

  final class PhaseAskCommandBuilder[C, Cmd] private[dsl] (
      private val resolveRef: C => RecipientRef[Cmd]
  ) {
    /** Step 2 of 3: build the command from `(transactionId, replyTo)`. */
    def command[Reply](buildCmd: (String, ActorRef[Reply]) => Cmd): PhaseAskMapReplyBuilder[C, Cmd, Reply] =
      new PhaseAskMapReplyBuilder[C, Cmd, Reply](resolveRef, buildCmd)
  }

  final class PhaseAskMapReplyBuilder[C, Cmd, Reply] private[dsl] (
      private val resolveRef: C => RecipientRef[Cmd],
      private val buildCmd: (String, ActorRef[Reply]) => Cmd
  ) {
    /** Step 3 of 3: map the reply into `Either[E, SagaResult[R]]`. */
    def mapReply[E, R](f: Reply => Either[E, SagaResult[R]]): PhaseAsk[E, R, C] =
      AskBinding(resolveRef, buildCmd, f)
  }

  private final case class AskBinding[Cmd, Reply, E, R, C](
      ref: C => RecipientRef[Cmd],
      command: (String, ActorRef[Reply]) => Cmd,
      mapReply: Reply => Either[E, SagaResult[R]]
  ) extends PhaseAsk[E, R, C] {
    // Intentionally no recover here: ask failures and mapReply exceptions surface as failed
    // futures and are classified by the owning AskParticipant (thrown track) — they never escape.
    override def send(transactionId: String, ctx: C, traceId: String)(implicit ec: ExecutionContext, s: Scheduler, timeout: Timeout): Future[Either[E, SagaResult[R]]] =
      ref(ctx).ask(replyTo => command(transactionId, replyTo)).map(mapReply)
  }

  private final case class DirectBinding[E, R, C](f: (String, C, String) => Future[Either[E, SagaResult[R]]]) extends PhaseAsk[E, R, C] {
    override def send(transactionId: String, ctx: C, traceId: String)(implicit ec: ExecutionContext, s: Scheduler, timeout: Timeout): Future[Either[E, SagaResult[R]]] =
      f(transactionId, ctx, traceId)
  }
}
