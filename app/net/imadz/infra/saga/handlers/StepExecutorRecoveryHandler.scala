package net.imadz.infra.saga.handlers

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.adapter.ClassicActorRefOps
import net.imadz.infra.saga.StepExecutor._
import net.imadz.infra.saga.SagaParticipant._

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

object StepExecutorRecoveryHandler {
  def onRecoveryCompleted[E, R](context: ActorContext[Command], state: State[E, R]): Unit = {

    val replyTo = state
      .replyTo.map(context.system.classicSystem.actorSelection)
      .map(_.resolveOne(3.seconds))
      .map(futureRef => Await.result(futureRef, 3.seconds).toTyped.asInstanceOf[ActorRef[StepResult[E, R]]])

    context.log.info(s"SAGA Transaction Step is Recovered on ${state}")
    state.status match {
      case Created | Succeed =>
        state.step.foreach { step =>
          context.log.info(s"TrxId: ${state.transactionId} Phase: ${step.phase} StepKey: ${step.stepId} | No recovery action for durability on status: ${state.status}")
        }
        ()
      case Failed =>
        state.lastError.zip(state.step).foreach { case (error, step) => error match {

          case RetryableFailure(msg) if state.retries < step.maxRetries =>
            context.log.info(s"TrxId: ${state.transactionId} Phase: ${step.phase} StepKey: ${step.stepId} | Take RetryOperation on RetryableFailure(${msg})")
            context.self ! RetryOperation(replyTo)
          case RetryableFailure(msg) =>
            context.log.info(s"TrxId: ${state.transactionId} Phase: ${step.phase} StepKey: ${step.stepId} | No RetryOperation needed on RetryableFailure(${msg}) since max retries reached.")
            ()
          case NonRetryableFailure(msg) =>
            context.log.info(s"TrxId: ${state.transactionId} Phase: ${step.phase} StepKey: ${step.stepId} | No RetryOperation needed on NonRetryableFailure(${msg}).")
            ()
        }
        }
      case Ongoing =>
        state.step.zip(state.transactionId).foreach { case (step, trxId) =>
          if (step.retryWhenRecoveredOngoing) {
            context.log.info(s"TrxId: ${state.transactionId} Phase: ${step.phase} StepKey: ${step.stepId} | Take RetryOperation on Ongoing state")

            context.self ! RecoverExecution(trxId, step, replyTo)
          } else {
            context.log.info(s"TrxId: ${state.transactionId} Phase: ${step.phase} StepKey: ${step.stepId} | No need to take RetryOperation on Ongoing state while retryWhenRecoveredOngoing is ${step.retryWhenRecoveredOngoing}")
          }
        }
    }
  }

}
