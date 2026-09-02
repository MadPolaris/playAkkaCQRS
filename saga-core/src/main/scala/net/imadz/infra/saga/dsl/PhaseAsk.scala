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

  /** Direct function for non-aggregate interactions (DB writes, HTTP calls, ...). */
  def direct[E, R, C](f: (String, C, String) => Future[Either[E, SagaResult[R]]]): PhaseAsk[E, R, C] = DirectBinding(f)

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
