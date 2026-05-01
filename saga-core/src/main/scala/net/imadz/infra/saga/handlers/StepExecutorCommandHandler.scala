package net.imadz.infra.saga.handlers

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.TimerScheduler
import akka.pattern.CircuitBreaker
import akka.persistence.typed.scaladsl.Effect
import net.imadz.infra.saga.SagaParticipant._
import net.imadz.infra.saga.SagaPhase._
import net.imadz.infra.saga.StepExecutor._
import net.imadz.infra.saga.{SagaParticipant, SagaTransactionStep}
import org.slf4j.LoggerFactory

import scala.concurrent.duration.FiniteDuration

object StepExecutorCommandHandler {
  def commandHandler[E, R, C](
                               actorContext: akka.actor.typed.scaladsl.ActorContext[Command],
                               context: C,
                               timers: TimerScheduler[Command],
                               defaultMaxRetries: Int,
                               initialRetryDelay: FiniteDuration,
                               circuitBreaker: CircuitBreaker
                             ): (State[E, R, C], Command) => Effect[Event, State[E, R, C]] = { (state, command) =>
    command match {
      case startMsg @ Start(transactionId, step, replyTo, traceId) if state.status == Succeed =>
        val typedReplyTo = replyTo.asInstanceOf[Option[ActorRef[StepResult[E, R, C]]]]
        typedReplyTo.foreach(_ ! StepCompleted[E, R, C](transactionId, step.stepId, state.result.get))
        Effect.none

      case startMsg @ Start(transactionId, step, replyTo, traceId) if state.status == Failed =>
        val typedReplyTo = replyTo.asInstanceOf[Option[ActorRef[StepResult[E, R, C]]]]
        typedReplyTo.foreach(_ ! StepFailed[E, R, C](transactionId, step.stepId, state.lastError.getOrElse(NonRetryableFailure("Unknown error"))))
        Effect.none

      case Start(transactionId, step, replyTo, traceId) if state.canStart =>
        Effect
          .persist(ExecutionStarted(transactionId, step, serializeActorRef(replyTo)(actorContext), traceId))
          .thenRun((updatedState: State[E, R, C]) => {
            actorContext.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(ExecutionStarted(transactionId, step, serializeActorRef(replyTo)(actorContext), traceId))
            executeOperation(actorContext, context, timers, step.phase, step, transactionId, traceId, circuitBreaker, replyTo)
          })

      case RecoverExecution(transactionId, step, replyTo, traceId) if state.canRecover =>
        Effect
          .persist(ExecutionStarted(transactionId, step, serializeActorRef(replyTo)(actorContext), traceId))
          .thenRun((updatedState: State[E, R, C]) => {
            actorContext.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(ExecutionStarted(transactionId, step, serializeActorRef(replyTo)(actorContext), traceId))
            executeOperation(actorContext, context, timers, step.phase, step, transactionId, traceId, circuitBreaker, replyTo)
          })


      case OperationResponse(Right(result), replyTo: Option[ActorRef[StepResult[E, R, C]]]) if state.status == Ongoing =>
        timers.cancel("StepTimeout")
        val txId = state.transactionId.get
        val sid = state.step.get.stepId
        val ph = state.step.get.phase.toString
        val tid = state.traceId.getOrElse("")
        Effect
          .persist(OperationSucceeded(txId, sid, ph, tid, result))
          .thenRun((updatedState: State[E, R, C]) => updatedState.status match {
            case Succeed => // Notify success
              actorContext.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(OperationSucceeded(txId, sid, ph, tid, result))
              replyTo.foreach(_ ! StepCompleted[E, R, C](txId, sid, result))
            case _ => // Unexpected state
          })
          .thenStop()

      case OperationResponse(Left(error: RetryableFailure), replyTo) if state.canScheduleRetryOnFailure(defaultMaxRetries) =>
        timers.cancel("StepTimeout")
        val nextRetry = state.retries + 1
        val nextDelay = calculateBackoffDelay(initialRetryDelay, nextRetry)
        val txId = state.transactionId.get
        val sid = state.step.get.stepId
        val ph = state.step.get.phase.toString
        val tid = state.traceId.getOrElse("")

        Effect
          .persist(List(OperationFailed(txId, sid, ph, tid, error), RetryScheduled(txId, sid, ph, tid, nextRetry)))
          .thenRun(_ => {
            actorContext.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(OperationFailed(txId, sid, ph, tid, error))
            actorContext.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(RetryScheduled(txId, sid, ph, tid, nextRetry))
            scheduleRetry(timers, nextDelay, replyTo)
          })

      case OperationResponse(Left(error), replyTo: Option[ActorRef[StepResult[E, R, C]]]) =>
        timers.cancel("StepTimeout")
        val txId = state.transactionId.get
        val sid = state.step.get.stepId
        val ph = state.step.get.phase.toString
        val tid = state.traceId.getOrElse("")

        Effect
          .persist(OperationFailed(txId, sid, ph, tid, error))
          .thenRun((_: State[E, R, C]) => {
            actorContext.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(OperationFailed(txId, sid, ph, tid, error))
            replyTo.foreach(_ ! StepFailed(txId, sid, error))
          })
          .thenStop()

      case TimedOut(replyTo) if state.status == Ongoing && state.canScheduleRetryOnTimedOut(defaultMaxRetries) =>
        actorContext.log.warn(s"TimedOut found ${state.retries} times")

        val nextRetry = state.retries + 1
        val nextDelay = calculateBackoffDelay(initialRetryDelay, nextRetry)
        val txId = state.transactionId.get
        val sid = state.step.get.stepId
        val ph = state.step.get.phase.toString
        val tid = state.traceId.getOrElse("")

        Effect
          .persist(List(OperationFailed(txId, sid, ph, tid, RetryableFailure("timed out")), RetryScheduled(txId, sid, ph, tid, nextRetry)))
          .thenRun(_ => {
            actorContext.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(OperationFailed(txId, sid, ph, tid, RetryableFailure("timed out")))
            actorContext.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(RetryScheduled(txId, sid, ph, tid, nextRetry))
            scheduleRetry(timers, nextDelay, replyTo)
          })

      case TimedOut(replyTo: Option[ActorRef[StepResult[E, R, C]]]) if state.status == Ongoing =>
        val txId = state.transactionId.get
        val sid = state.step.get.stepId
        val ph = state.step.get.phase.toString
        val tid = state.traceId.getOrElse("")
        Effect
          .persist(OperationFailed(txId, sid, ph, tid, RetryableFailure("timed out")))
          .thenRun((_: State[E, R, C]) => {
            actorContext.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(OperationFailed(txId, sid, ph, tid, RetryableFailure("timed out")))
            replyTo.foreach(_ ! StepFailed(txId, sid, RetryableFailure("timed out")))
          })
          .thenStop()

      case TimedOut(_) =>
        actorContext.log.info(s"TrxId: ${state.transactionId} | Ignoring TimedOut message because step is already in ${state.status} state")
        Effect.none

      case RetryOperation(replyTo: Option[ActorRef[StepResult[E, R, C]]]) if state.canRetry =>
        state.step.zip(state.transactionId).zip(state.traceId).map {
          case ((step, trxId), traceId) =>
            Effect.none[Event, State[E, R, C]]
              .thenRun(_ => executeOperation[E, R, C](actorContext, context, timers, step.phase, step, trxId, traceId, circuitBreaker, replyTo))
        }.getOrElse(Effect.none)

      case qs: QueryStatus[E, R, C] =>
        qs.replyTo ! state
        Effect.none

      case ManualFix(replyTo) =>
        timers.cancel("StepTimeout")
        actorContext.log.info(s"ManualFix received for transaction ${state.transactionId.getOrElse("unknown")} step ${state.step.map(_.stepId).getOrElse("unknown")}")
        val typedReplyTo = replyTo.asInstanceOf[Option[ActorRef[StepResult[E, R, C]]]]
        // Assume manual fix is successful, return an empty successful SagaResult
        val manualResult = net.imadz.infra.saga.SagaParticipant.SagaResult.empty[R]()
        val txId = state.transactionId.get
        val sid = state.step.get.stepId
        val ph = state.step.get.phase.toString
        val tid = state.traceId.getOrElse("")

        Effect
          .persist(ManualFixCompleted(txId, sid, ph, tid, manualResult))
          .thenRun((updatedState: State[E, R, C]) => {
             actorContext.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(ManualFixCompleted(txId, sid, ph, tid, manualResult))
             typedReplyTo.foreach(_ ! StepCompleted[E, R, C](txId, sid, manualResult))
          })
          .thenStop()

      case msg =>
        actorContext.log.warn(s"msg: $msg is not processed")
        Effect.none
    }
  }

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Executes the operation associated with the saga step.
   */
  private def executeOperation[E, R, C](
                                         actorContext: akka.actor.typed.scaladsl.ActorContext[Command],
                                         context: C,
                                         timers: TimerScheduler[Command],
                                         stepPhase: TransactionPhase,
                                         step: SagaTransactionStep[E, R, C],
                                         transactionId: String,
                                         traceId: String,
                                         circuitBreaker: CircuitBreaker,
                                         replyTo: Option[ActorRef[StepResult[E, R, C]]]
                                       ): Unit = {
    import actorContext.executionContext

    timers.startSingleTimer("StepTimeout", TimedOut(replyTo), step.timeoutDuration)

    val eventualStepResult: SagaParticipant.ParticipantEffect[RetryableOrNotException, R] = stepPhase match {
      case PreparePhase =>
        step.participant.prepare(transactionId, context, traceId)
      case CommitPhase =>
        step.participant.commit(transactionId, context, traceId)
      case CompensatePhase =>
        step.participant.compensate(transactionId, context, traceId)
    }

    circuitBreaker.withCircuitBreaker(eventualStepResult).onComplete {
      case scala.util.Success(result: Either[RetryableOrNotException, SagaResult[R]]) =>
        actorContext.self ! OperationResponse(result, replyTo)
      case scala.util.Failure(exception) =>
        logger.error(s"Operation failed with exception for step ${step.stepId}", exception)
        actorContext.self ! OperationResponse(Left(step.participant.classify(exception)), replyTo)
    }
  }

  private def scheduleRetry[E, R, C](timers: TimerScheduler[Command], delay: FiniteDuration, replyTo: Option[ActorRef[StepResult[E, R, C]]]): Unit = {
    timers.startSingleTimer(RetryOperation(replyTo), delay)
  }

  private def calculateBackoffDelay(initialDelay: FiniteDuration, retryCount: Int): FiniteDuration = {
    initialDelay * math.pow(2, retryCount - 1).toLong
  }


  /**
   * Serializes an ActorRef into a string format that can be persisted.
   */
  private def serializeActorRef(replyTo: Option[ActorRef[_]])(implicit context: akka.actor.typed.scaladsl.ActorContext[_]): String = {
    replyTo.map(ref => akka.actor.typed.ActorRefResolver(context.system).toSerializationFormat(ref)).getOrElse("")
  }

}
