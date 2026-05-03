package net.imadz.infra.saga.handlers

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.{ActorContext, TimerScheduler}
import akka.persistence.typed.scaladsl.Effect
import akka.util.Timeout
import net.imadz.infra.saga.SagaParticipant.{NonRetryableFailure, RetryableOrNotException}
import net.imadz.infra.saga.SagaPhase._
import net.imadz.infra.saga.SagaTransactionCoordinator._
import net.imadz.infra.saga.StepExecutor.{StepCompleted, StepFailed}
import net.imadz.infra.saga.model.ExecutionPlan
import net.imadz.infra.saga.{SagaTransactionStep, StepExecutor}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

/**
 * Handles commands for the SagaTransactionCoordinator.
 * Implements a push-based asynchronous messaging model using ExecutionPlan and ExecutionProgress.
 */
object SagaTransactionCoordinatorCommandHandler {

  /**
   * The command handler function for the SagaTransactionCoordinator.
   */
  def commandHandler(
                      context: ActorContext[Command],
                      timers: TimerScheduler[Command],
                      stepExecutorFactory: (String, SagaTransactionStep[_, _, _]) => ActorRef[StepExecutor.Command],
                      globalTimeout: FiniteDuration
                    )(implicit ec: ExecutionContext): (State, Command) => Effect[Event, State] = { (state, command) =>
    command match {
      case StartTransaction(transactionId, steps, replyTo, reqTraceId, singleStep) if state.status == Created =>
        val traceId = if (reqTraceId.isEmpty) transactionId else reqTraceId
        timers.startSingleTimer(TransactionTimeoutKey, TransactionTimeout, globalTimeout)
        
        Effect.persist[Event, State](TransactionStarted(transactionId, steps, traceId, singleStep))
          .thenRun { (stateNew: State) =>
            context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(TransactionStarted(transactionId, steps, traceId))
            if (singleStep) {
              context.self ! TransactionPaused(transactionId, traceId)
            } else {
              startPhase(context, stateNew, stepExecutorFactory, replyTo)
            }
          }

      case TransactionTimeout =>
        if (state.status == InProgress || state.status == Compensating) {
          context.log.warn(s"[TraceID: ${state.traceId}] Transaction ${state.transactionId.getOrElse("")} timed out at phase ${state.currentPhase}")
          handlePhaseFailure(context, state, state.currentPhase, NonRetryableFailure("Global transaction timeout"), stepExecutorFactory, None)
        } else {
          Effect.none
        }

      case ProceedNext(replyTo) if state.isPaused =>
        Effect.persist[Event, State](TransactionResumed(state.transactionId.get, state.traceId))
          .thenRun { (stateNew: State) =>
            startPhase(context, stateNew, stepExecutorFactory, replyTo)
          }

      case ResolveSuspended(replyTo) if state.status == Suspended =>
        Effect.persist[Event, State](TransactionResolved(state.transactionId.get))
          .thenRun { (stateNew: State) =>
            context.log.info(s"[TraceID: ${state.traceId}] Resolving suspended transaction ${state.transactionId.getOrElse("")}, re-executing phase ${state.currentPhase} at group ${stateNew.currentStepGroup}")
            startPhase(context, stateNew, stepExecutorFactory, replyTo)
          }

      case ProceedNextGroup(replyTo) =>
        if ((state.status == InProgress || state.status == Compensating) && !state.isPaused) {
          context.log.info(s"[TraceID: ${state.traceId}] Proceeding to next group ${state.currentStepGroup} in phase ${state.currentPhase}")
          startPhase(context, state, stepExecutorFactory, replyTo)
          Effect.none
        } else {
          context.log.info(s"Ignoring ProceedNextGroup because state is ${state.status} and paused=${state.isPaused}")
          Effect.none
        }

      case RetryCurrentPhase(replyTo) =>
        val phaseToRetry = if (state.status == Compensating && state.currentPhase == CompensatePhase) PreparePhase else state.currentPhase
        Effect.persist(TransactionRetried(state.transactionId.get, phaseToRetry)).thenRun { (stateNew: State) =>
          context.log.info(s"[TraceID: ${state.traceId}] Retrying phase ${stateNew.currentPhase} at group ${stateNew.currentStepGroup}")
          startPhase(context, stateNew, stepExecutorFactory, replyTo)
        }

      case ManualFixStep(stepId, phase, replyTo) =>
        state.transactionId.foreach { tid =>
          state.steps.find(s => s.stepId == stepId && s.phase == phase).foreach { step =>
            val name = s"$tid-$stepId-$phase"
            val executor = context.child(name).map(_.asInstanceOf[ActorRef[StepExecutor.Command]])
              .getOrElse(stepExecutorFactory(name, step))
            executor ! StepExecutor.ManualFix(None)
          }
        }
        Effect.none

      case p: TransactionPaused =>
        Effect.persist[Event, State](p).thenRun { (_: State) =>
          context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(TransactionSuspended(p.transactionId, "MANUAL_PAUSE"))
        }

      case PhaseCompleted(phase, results, replyTo) =>
        handlePhaseCompletion(context, state, phase, results, stepExecutorFactory, replyTo)

      case PhaseFailure(phase, error, replyTo) =>
        handlePhaseFailure(context, state, phase, error, stepExecutorFactory, replyTo)

      case InternalStepResult(result, replyTo) =>
        handleStepResult(context, state, result, stepExecutorFactory, replyTo)

      case StartStepGroup(phase, group, stepIds, replyTo) =>
        Effect.persist(StepGroupStarted(state.transactionId.get, phase, group, stepIds)).thenRun { (stateNew: State) =>
          val newPlan = ExecutionPlan(stateNew.steps)
          val stepsToStart = newPlan.getSteps(phase, group)
          
          val replyAdapter = context.messageAdapter[StepExecutor.StepResult[_, _, _]](res => InternalStepResult(res, replyTo))
          
          stepsToStart.foreach { step =>
            val name = s"${stateNew.transactionId.get}-${step.stepId}-${phase}"
            val executor = context.child(name).map(_.asInstanceOf[ActorRef[StepExecutor.Command]])
              .getOrElse(stepExecutorFactory(name, step))
            executor ! StepExecutor.Start(stateNew.transactionId.get, step.asInstanceOf[SagaTransactionStep[Any, Any, Any]], Some(replyAdapter.asInstanceOf[ActorRef[StepExecutor.StepResult[Any, Any, Any]]]), stateNew.traceId)
          }
        }

      case _ => Effect.none
    }
  }

  /**
   * Starts the execution of the current group of steps in the current phase.
   */
  private def startPhase(
                          context: ActorContext[Command],
                          state: State,
                          stepExecutorFactory: (String, SagaTransactionStep[_, _, _]) => ActorRef[StepExecutor.Command],
                          replyTo: Option[ActorRef[TransactionResult]]
                        ): Unit = {
    val plan = ExecutionPlan(state.steps)
    val stepsInPhase = plan.getSteps(state.currentPhase, state.currentStepGroup).filter { step =>
      if (state.currentPhase == CompensatePhase) state.successfulSteps.contains(step.stepId)
      else true
    }

    if (stepsInPhase.isEmpty) {
      context.log.info(s"[TraceID: ${state.traceId}] No steps found for phase ${state.currentPhase} and group ${state.currentStepGroup}, completing group/phase...")
      context.self ! PhaseCompleted(state.currentPhase, Nil, replyTo)
    } else {
      val stepIds = stepsInPhase.map(_.stepId).toSet
      context.self ! StartStepGroup(state.currentPhase, state.currentStepGroup, stepIds, replyTo)
    }
  }

  /**
   * Handles the result of a single step execution.
   */
  private def handleStepResult(
                                context: ActorContext[Command],
                                state: State,
                                result: StepExecutor.StepResult[_, _, _],
                                stepExecutorFactory: (String, SagaTransactionStep[_, _, _]) => ActorRef[StepExecutor.Command],
                                replyTo: Option[ActorRef[TransactionResult]]
                              ): Effect[Event, State] = {
    val (resultTxId, stepId) = result match {
      case StepCompleted(tid, sid, _) => (tid, sid)
      case StepFailed(tid, sid, _) => (tid, sid)
    }
    
    if (!state.transactionId.contains(resultTxId)) {
      context.log.warn(s"Ignoring StepResult for mismatched transactionId. Expected ${state.transactionId}, got $resultTxId")
      return Effect.none
    }
    
    val sagaResult: Either[RetryableOrNotException, Any] = result match {
      case StepCompleted(_, _, res) => Right(res)
      case StepFailed(_, _, err) => Left(err)
    }

    Effect.persist(StepResultReceived(state.transactionId.get, stepId, sagaResult)).thenRun { (stateNew: State) =>
      if (stateNew.pendingSteps.isEmpty) {
        val firstError = stateNew.phaseResults.collectFirst { case Left(err) => err }
        firstError match {
          case Some(err) => context.self ! PhaseFailure(stateNew.currentPhase, err, replyTo)
          case None => context.self ! PhaseCompleted(stateNew.currentPhase, stateNew.phaseResults, replyTo)
        }
      }
    }
  }

  private def handlePhaseCompletion(
                                     context: ActorContext[Command],
                                     state: State,
                                     phase: TransactionPhase,
                                     results: List[Either[RetryableOrNotException, Any]],
                                     stepExecutorFactory: (String, SagaTransactionStep[_, _, _]) => ActorRef[StepExecutor.Command],
                                     replyTo: Option[ActorRef[TransactionResult]]
                                   ): Effect[Event, State] = {

    val plan = ExecutionPlan(state.steps)

    phase match {
      case PreparePhase =>
        plan.nextGroup(PreparePhase, state.currentStepGroup) match {
          case Some(nextGroup) =>
            Effect.persist(StepGroupSucceeded(state.transactionId.get, PreparePhase, state.currentStepGroup)).thenRun { (stateNew: State) =>
              context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(StepGroupSucceeded(state.transactionId.get, PreparePhase, state.currentStepGroup))
              if (state.singleStep) {
                context.self ! TransactionPaused(stateNew.transactionId.get, stateNew.traceId)
              } else {
                context.self ! ProceedNextGroup(replyTo)
              }
            }
          case None =>
            val persistEffect = Effect.persist[Event, State](PhaseSucceeded(state.transactionId.get, PreparePhase))
            if (state.singleStep) {
              persistEffect.thenRun { (stateNew: State) =>
                context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(PhaseSucceeded(state.transactionId.get, PreparePhase))
                context.self ! TransactionPaused(stateNew.transactionId.get, stateNew.traceId)
              }
            } else {
              persistEffect.thenRun { (stateNew: State) =>
                context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(PhaseSucceeded(state.transactionId.get, PreparePhase))
                startPhase(context, stateNew, stepExecutorFactory, replyTo)
              }
            }
        }

      case CommitPhase =>
        plan.nextGroup(CommitPhase, state.currentStepGroup) match {
          case Some(nextGroup) =>
            Effect.persist(StepGroupSucceeded(state.transactionId.get, CommitPhase, state.currentStepGroup)).thenRun { (_: State) =>
              context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(StepGroupSucceeded(state.transactionId.get, CommitPhase, state.currentStepGroup))
              context.self ! ProceedNextGroup(replyTo)
            }
          case None =>
            Effect.persist(List(PhaseSucceeded(state.transactionId.get, CommitPhase), TransactionCompleted(state.transactionId.get)))
              .thenRun { (stateNew: State) =>
                context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(PhaseSucceeded(state.transactionId.get, CommitPhase))
                context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(TransactionCompleted(state.transactionId.get))
                replyTo.foreach(_ ! TransactionResult(successful = true, stateNew))
              }
        }

      case CompensatePhase =>
        plan.nextGroup(CompensatePhase, state.currentStepGroup) match {
          case Some(nextGroup) =>
            Effect.persist(StepGroupSucceeded(state.transactionId.get, CompensatePhase, state.currentStepGroup)).thenRun { (_: State) =>
              context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(StepGroupSucceeded(state.transactionId.get, CompensatePhase, state.currentStepGroup))
              context.self ! ProceedNextGroup(replyTo)
            }
          case None =>
            Effect.persist(List(PhaseSucceeded(state.transactionId.get, CompensatePhase), TransactionFailed(state.transactionId.get, "transaction failed but compensated")))
              .thenRun { (stateNew: State) =>
                context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(PhaseSucceeded(state.transactionId.get, CompensatePhase))
                context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(TransactionFailed(state.transactionId.get, "transaction failed but compensated"))
                replyTo.foreach(_ ! TransactionResult(successful = false, stateNew))
              }
        }
    }
  }

  private def handlePhaseFailure(
                                  context: ActorContext[Command],
                                  state: State,
                                  phase: TransactionPhase,
                                  error: RetryableOrNotException,
                                  stepExecutorFactory: (String, SagaTransactionStep[_, _, _]) => ActorRef[StepExecutor.Command],
                                  replyTo: Option[ActorRef[TransactionResult]]
                                ): Effect[Event, State] = {
    if (phase != CompensatePhase) {
      state.transactionId match {
        case Some(tid) =>
          val persistEffect = Effect.persist[Event, State](PhaseFailed(tid, phase))
          if (state.singleStep) {
            persistEffect.thenRun { (stateNew: State) =>
              context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(PhaseFailed(tid, phase))
              context.self ! TransactionPaused(tid, stateNew.traceId)
            }
          } else {
            persistEffect.thenRun { (stateNew: State) =>
              context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(PhaseFailed(tid, phase))
              startPhase(context, stateNew, stepExecutorFactory, replyTo)
            }
          }
        case None =>
          context.log.error(s"Phase $phase failed but transactionId is missing in state!")
          Effect.none
      }
    } else {
      val reason = s"Phase $phase failed with error: ${error.message}"
      state.transactionId match {
        case Some(tid) =>
          Effect.persist(List(PhaseFailed(tid, phase), TransactionSuspended(tid, reason)))
            .thenRun { (stateNew: State) =>
              context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(PhaseFailed(tid, phase))
              context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(TransactionSuspended(tid, reason))
              replyTo.foreach(_ ! TransactionResult(successful = false, stateNew, reason))
            }
        case None =>
          context.log.error(s"Phase $phase failed but transactionId is missing in state!")
          Effect.none
      }
    }
  }
}
