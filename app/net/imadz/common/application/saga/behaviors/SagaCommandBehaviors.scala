package net.imadz.common.application.saga.behaviors

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.ActorContext
import akka.pattern.{CircuitBreaker, CircuitBreakerOpenException}
import akka.persistence.typed.scaladsl.Effect
import net.imadz.common.application.saga.TransactionCoordinator._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success}

object SagaCommandBehaviors {
  private val MaxRetries = 5
  private val InitialRetryDelay = 100.milliseconds
  private val MaxRetryDelay = 5.seconds

  // Circuit Breaker configuration
  private val MaxFailures = 5
  private val ResetTimeout = 30.seconds
  private val CallTimeout = 5.seconds

  def commandHandler(context: ActorContext[SagaCommand])(implicit ec: ExecutionContext): (State, SagaCommand) => Effect[SagaEvent, State] = { (state, command) =>
    val circuitBreaker = CircuitBreaker(context.system.classicSystem.scheduler, MaxFailures, CallTimeout, ResetTimeout)

    command match {
      case StartTransaction(transaction, replyTo) if state.currentTransaction.isEmpty =>
        Effect.persist(
          List(
            TransactionStarted(transaction),
            TransactionPhaseStarted("prepare"))
        ).thenRun(state => startPreparePhase(context, Some(replyTo))(state))

      case StartNextStep(replyTo) =>
        startNextStep(context, replyTo, circuitBreaker)(state)

      case CompleteStep(step, success, replyTo) =>
        if (success) {
          Effect
            .persist(StepCompleted(step, success))
            .thenRun(_ => continuePhase(context, replyTo)(state))
        } else {
          handleStepFailure(context, step, replyTo, circuitBreaker)
        }

      case RetryStep(step, replyTo) =>
        val retryDelay = calculateRetryDelay(step.retries)
        context.scheduleOnce(retryDelay, context.self, ExecuteStep(step, replyTo))
        Effect.none
      case ExecuteStep(step, replyTo) =>
        Effect.persist(TransactionStepStarted(step))
          .thenRun(_ => executeStep(context, step, state.currentTransaction.get, state.currentPhase, replyTo, circuitBreaker))

      case StepTimeout(step, replyTo) =>
        handleStepFailure(context, step, replyTo, circuitBreaker)

      case CompensateStep(step, replyTo) =>
        Effect.persist(TransactionStepStarted(step))
          .thenRun(_ => executeCompensation(context, step, state.currentTransaction.get, replyTo))

      case CompletePhase("prepare", true, replyTo) =>
        Effect.persist(
          List(
            PhaseCompleted("prepare", true),
            TransactionPhaseStarted("commit")
          )).thenRun(_ => startCommitPhase(context, replyTo))

      case CompletePhase("commit", true, maybeReplyTo) =>
        val baseEffect = Effect.persist[SagaEvent, State](
          List(
            PhaseCompleted("commit", true),
            TransactionCompleted(true)
          ))

        maybeReplyTo
          .map(baseEffect.thenReply[TransactionResponse](_)(_ => TransactionResponse.Completed(state.currentTransaction.get.id)))
          .getOrElse(baseEffect)

      case CompletePhase("compensate", success, maybeReplyTo) =>
        val baseEffect = Effect
          .persist[SagaEvent, State](List(
            PhaseCompleted("compensate", success),
            TransactionCompleted(false)
          ))
        maybeReplyTo.map(
          baseEffect
            .thenReply[TransactionResponse](_)(state =>
              TransactionResponse.PartiallyCompleted(
                state.currentTransaction.get.id,
                state.completedSteps.toSeq,
                state.failedSteps.toSeq
              )
            )
        ).getOrElse(baseEffect)

      case CompletePhase(phase, success, maybeReplyTo) =>
        Effect.persist(PhaseCompleted(phase, success))
    }
  }


  private def startPreparePhase(context: ActorContext[SagaCommand], replyTo: Option[ActorRef[TransactionResponse]]): State => Unit = { _ =>
    context.self ! StartNextStep(replyTo)
  }

  private def startNextStep(context: ActorContext[SagaCommand], maybeReplyTo: Option[ActorRef[TransactionResponse]], circuitBreaker: CircuitBreaker)(state: State)(implicit ec: ExecutionContext): Effect[SagaEvent, State] = {
    state.currentTransaction match {
      case Some(transaction) if state.currentPhase.nonEmpty =>
        transaction.steps.find(step =>
          step.phase == state.currentPhase && !state.completedSteps.contains(step) && !state.compensatedSteps.contains(step)
        ) match {
          case Some(step) =>
            val isRecoveryProcess = maybeReplyTo.isEmpty

            if (isRecoveryProcess && step.status == StepOngoing) {
              Effect.none.thenRun(_ => executeStep(context, step, transaction, state.currentPhase, maybeReplyTo, circuitBreaker))
            } else if (step.status == StepCreated || !isRecoveryProcess) {
              Effect
                .persist(TransactionStepStarted(step))
                .thenRun(_ => executeStep(context, step, transaction, state.currentPhase, maybeReplyTo, circuitBreaker))
            } else {
              Effect.none.thenRun(_ => executeStep(context, step, transaction, state.currentPhase, maybeReplyTo, circuitBreaker))
            }

          case None =>
            Effect.persist(PhaseCompleted(state.currentPhase, success = true))
              .thenRun(_ => context.self ! CompletePhase(state.currentPhase, success = true, maybeReplyTo))
        }
      case _ =>
        Effect.none
    }
  }

  private def handleStepFailure(
    context: ActorContext[SagaCommand],
    step: TransactionStep,
    replyTo: Option[ActorRef[TransactionResponse]],
    circuitBreaker: CircuitBreaker
  )(implicit ec: ExecutionContext): Effect[SagaEvent, State] = {
    if (circuitBreaker.isClosed) {
      if (step.retries < MaxRetries) {
        Effect.persist(StepFailed(step, s"Step failed, retrying (attempt ${step.retries + 1})"))
          .thenRun(_ => {
            val retryDelay = calculateRetryDelay(step.retries)
            context.scheduleOnce(retryDelay, context.self, RetryStep(step.copy(retries = step.retries + 1), replyTo))
          })
      } else {
        Effect.persist(
          List(
            StepFailed(step, "Max retries reached"),
            TransactionPhaseStarted("compensate")
          )).thenRun(_ => startCompensationPhase(context, replyTo))
      }
    } else {
      // Circuit is open, we should not retry and instead move to compensation
      Effect.persist(
        List(
          StepFailed(step, "Circuit breaker is open, cannot retry"),
          TransactionPhaseStarted("compensate")
        )).thenRun(_ => {
        context.log.warn(s"Circuit breaker is open for step ${step.id}, moving to compensation phase")
        startCompensationPhase(context, replyTo)
      })
    }
  }

  private def calculateRetryDelay(retries: Int): FiniteDuration = {
    val delay = InitialRetryDelay * Math.pow(2, retries).toLong
    delay.min(MaxRetryDelay)
  }

  private def executeStep(
    context: ActorContext[SagaCommand],
    step: TransactionStep,
    transaction: Transaction,
    currentPhase: String,
    maybeReplyTo: Option[ActorRef[TransactionResponse]],
    circuitBreaker: CircuitBreaker
  )(implicit ec: ExecutionContext): Unit = {
    val stepExecution = () => currentPhase match {
      case "prepare" => step.participant.prepare(transaction.id, Map.empty)
      case "commit" => step.participant.commit(transaction.id, Map.empty)
      case "compensate" => step.participant.compensate(transaction.id, Map.empty)
    }

    val futureWithCircuitBreaker = circuitBreaker.withCircuitBreaker(stepExecution())

    context.scheduleOnce(step.timeoutDuration, context.self, StepTimeout(step, maybeReplyTo))

    futureWithCircuitBreaker.onComplete {
      case Success(success) => context.self ! CompleteStep(step, success, maybeReplyTo)
      case Failure(_: CircuitBreakerOpenException) =>
        context.self ! CompleteStep(step, success = false, maybeReplyTo)
      case Failure(e: RetryableFailure) =>
        context.self ! CompleteStep(step, success = false, maybeReplyTo)
      case Failure(e: NonRetryableFailure) =>
        context.self ! CompleteStep(step, success = false, maybeReplyTo)
      case Failure(_) =>
        context.self ! CompleteStep(step, success = false, maybeReplyTo)
    }
  }

  private def executeCompensation(
    context: ActorContext[SagaCommand],
    step: TransactionStep,
    transaction: Transaction,
    maybeReplyTo: Option[ActorRef[TransactionResponse]]
  )(implicit ec: ExecutionContext): Unit = {
    step.participant.compensate(transaction.id, Map.empty).onComplete {
      case scala.util.Success(_) => context.self ! CompensateStep(step, maybeReplyTo)
      case scala.util.Failure(_) => context.self ! CompensateStep(step, maybeReplyTo)
    }
  }

  private def startCommitPhase(context: ActorContext[SagaCommand], replyTo: Option[ActorRef[TransactionResponse]]): Unit = {
    context.self ! StartNextStep(replyTo)
  }

  private def startCompensationPhase(context: ActorContext[SagaCommand], replyTo: Option[ActorRef[TransactionResponse]]): Unit = {
    context.self ! StartNextStep(replyTo)
  }

  private def continuePhase(context: ActorContext[SagaCommand], replyTo: Option[ActorRef[TransactionResponse]])(state: State): Unit = {
    context.self ! StartNextStep(replyTo)
  }
}
