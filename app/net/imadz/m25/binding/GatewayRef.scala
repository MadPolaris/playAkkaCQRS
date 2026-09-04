package net.imadz.m25.binding

import akka.actor.typed.ActorRef

/**
 * Abstract reference to an external system gateway (Anti-Corruption Layer entry point).
 *
 * The FSM template never knows what protocol the external system speaks — XML, JSON,
 * SFTP, or raw TCP.  It only knows that `tell(msg)` delivers a typed command to the
 * gateway actor, and the gateway replies to `replyTo`.
 *
 * This is the single seam that keeps the Anti-Corruption Layer a first-class citizen:
 * templates depend on GatewayRef, not on EamsSystemServices or P2BService directly.
 */
trait GatewayRef[-T] {
  def tell(msg: T)(replyTo: ActorRef[_]): Unit
}

/**
 * Concrete gateway reference wrapping an Akka ActorRef.
 *
 * Usage:
 * {{{
 *   val gwSftp: GatewayRef[SftpCommand] = ActorGatewayRef(sftpServiceActor)
 *   val gwCore: GatewayRef[CoreApiCommand] = ActorGatewayRef(coreApiActor)
 * }}}
 */
final class ActorGatewayRef[T](ref: ActorRef[T]) extends GatewayRef[T] {
  override def tell(msg: T)(replyTo: ActorRef[_]): Unit =
    ref ! msg  // The msg itself carries the replyTo in its protocol
}

object ActorGatewayRef {
  def apply[T](ref: ActorRef[T]): GatewayRef[T] = new ActorGatewayRef[T](ref)
}
