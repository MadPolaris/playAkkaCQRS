package net.imadz.infra.saga.dsl

import akka.actor.typed.Scheduler
import akka.util.Timeout
import net.imadz.infra.saga.SagaParticipant
import net.imadz.infra.saga.SagaParticipant.{ParticipantEffect, RetryableOrNotException, SagaResult}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Pure-data participant with per-phase ask bindings. Zero serialization burden:
 * participants are pure functions of (definition, args) and are never journaled.
 *
 * The public prepare/commit/compensate are final — classification is cohesive here
 * (business rules fire at the ask boundary while E is still typed; thrown rules apply
 * on top of the default matrix). The abstract doXxx of the engine SPI are no-ops:
 * the engine only ever calls the public phase methods.
 */
abstract class AskParticipant[E, R, C](
    rules: ErrorRules[E] = ErrorRules.none[E],
    val askTimeout: scala.concurrent.duration.FiniteDuration = 5.seconds
)(implicit ec: ExecutionContext, scheduler: Scheduler)
    extends SagaParticipant[E, R, C] {

  protected def prepareBinding: Option[PhaseAsk[E, R, C]] = None
  protected def commitBinding: Option[PhaseAsk[E, R, C]] = None
  protected def compensateBinding: Option[PhaseAsk[E, R, C]] = None

  final override def prepare(transactionId: String, context: C, traceId: String)(implicit ec: ExecutionContext): ParticipantEffect[RetryableOrNotException, R] =
    run(prepareBinding, transactionId, context, traceId)

  final override def commit(transactionId: String, context: C, traceId: String)(implicit ec: ExecutionContext): ParticipantEffect[RetryableOrNotException, R] =
    run(commitBinding, transactionId, context, traceId)

  final override def compensate(transactionId: String, context: C, traceId: String)(implicit ec: ExecutionContext): ParticipantEffect[RetryableOrNotException, R] =
    run(compensateBinding, transactionId, context, traceId)

  // Engine SPI satisfaction — never invoked (the engine's only entry points are the public phase methods above).
  final override protected def doPrepare(transactionId: String, context: C, traceId: String): ParticipantEffect[E, R] = unsupported
  final override protected def doCommit(transactionId: String, context: C, traceId: String): ParticipantEffect[E, R] = unsupported
  final override protected def doCompensate(transactionId: String, context: C, traceId: String): ParticipantEffect[E, R] = unsupported
  final override protected def customClassification: PartialFunction[Throwable, RetryableOrNotException] = PartialFunction.empty

  private def run(binding: Option[PhaseAsk[E, R, C]], transactionId: String, context: C, traceId: String)(implicit ec: ExecutionContext): ParticipantEffect[RetryableOrNotException, R] = {
    implicit val timeout: Timeout = Timeout(askTimeout)
    binding match {
      case None => Future.successful(Right(SagaResult.empty[R]()))
      case Some(b) =>
        // A synchronous throw from the binding itself belongs to the thrown track too.
        scala.util.Try(b.send(transactionId, context, traceId)(ec, scheduler, timeout)) match {
          case scala.util.Success(outcome) =>
            outcome
              .map {
                case Right(r) => Right(r): Either[RetryableOrNotException, SagaResult[R]]
                case Left(e)  => Left(rules.classifyBusiness(e)) // business track: E still typed here
              }
              .recover { case t: Throwable => Left(rules.classifyThrown(t)) } // thrown track: ask failures, mapReply exceptions
          case scala.util.Failure(t) =>
            Future.successful(Left(rules.classifyThrown(t)))
        }
    }
  }

  private def unsupported: ParticipantEffect[E, R] =
    Future.failed(new UnsupportedOperationException("AskParticipant executes through its public phase methods"))
}
