package net.imadz.infra.saga.handlers

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.{ActorContext, TimerScheduler}
import net.imadz.infra.saga.SagaTransactionCoordinator._
import net.imadz.infra.saga.model.ExecutionPlan
import net.imadz.infra.saga.{SagaTransactionStep, StepExecutor}

import scala.concurrent.duration.FiniteDuration

/**
 * Handles recovery signals for the SagaTransactionCoordinator.
 */
object SagaTransactionCoordinatorRecoveryHandler {

  /**
   * Called when recovery is completed.
   */
  def onRecoveryCompleted(
                           context: ActorContext[Command],
                           timers: TimerScheduler[Command],
                           state: State,
                           stepExecutorFactory: (String, SagaTransactionStep[_, _, _]) => ActorRef[StepExecutor.Command],
                           globalTimeout: FiniteDuration
                         ): Unit = {
    context.log.info(s"[TraceID: ${state.traceId}] RecoveryCompleted for coordinator, state: $state")
    if ((state.status == InProgress || state.status == Compensating) && !state.isPaused) {
      val actualTimeout = state.timeout.getOrElse(globalTimeout)
      timers.startSingleTimer(TransactionTimeoutKey, TransactionTimeout, actualTimeout)
      context.log.info(s"[TraceID: ${state.traceId}] Resuming transaction ${state.transactionId.getOrElse("")} from phase ${state.currentPhase} with timeout $actualTimeout")
      
      val plan = ExecutionPlan(state.steps)
      val stepsInCurrentGroup = plan.getSteps(state.currentPhase, state.currentStepGroup)
      val stepIds = stepsInCurrentGroup.map(_.stepId).toSet
      
      context.self ! StartStepGroup(state.currentPhase, state.currentStepGroup, stepIds, None)
    } else if (state.isPaused) {
      context.log.info(s"[TraceID: ${state.traceId}] Transaction ${state.transactionId.getOrElse("")} recovered in PAUSED state. Waiting for ProceedNext.")
    }
  }
}
