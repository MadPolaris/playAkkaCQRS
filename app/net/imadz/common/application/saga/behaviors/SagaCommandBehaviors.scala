package net.imadz.common.application.saga.behaviors

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import net.imadz.common.application.saga.TransactionCoordinator._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object SagaCommandBehaviors {

  def commandHandler(context: ActorContext[SagaCommand])(implicit ec: ExecutionContext): (State, SagaCommand) => Effect[SagaEvent, State] = { (state, command) =>
    command match {
      case StartTransaction(transaction, replyTo) if state.currentTransaction.isEmpty =>
        Effect.persist(
          List(
            TransactionStarted(transaction),
            TransactionPhaseStarted("prepare"))
        ).thenRun(state => startPreparePhase(context, Some(replyTo))(state))

      case StartNextStep(replyTo) =>
        startNextStep(context, replyTo)(state)

      case CompleteStep(step, success, replyTo) =>
        if (success) {
          Effect
            .persist(StepCompleted(step, success))
            .thenRun(_ => continuePhase(context, replyTo)(state))
        } else {
          Effect.persist(
            List(
              StepCompleted(step, success),
              TransactionPhaseStarted("rollback")
            )).thenRun(_ => startRollbackPhase(context, state, replyTo))
        }

      case CompletePhase("prepare", true, replyTo) =>
        Effect.persist(
          List(
            PhaseCompleted("prepare", true),
            TransactionPhaseStarted("commit")
          )).thenRun(_ => startCommitPhase(context, state, replyTo))

      case CompletePhase("commit", true, maybeReplyTo) =>
        val baseEffect = Effect.persist[SagaEvent, State](
          List(
            PhaseCompleted("commit", true),
            TransactionCompleted(true)
          ))

        maybeReplyTo
          .map(baseEffect.thenReply[TransactionResponse](_)(_ => TransactionResponse.Completed(state.currentTransaction.get.id)))
          .getOrElse(baseEffect)

      case CompletePhase("rollback", success, maybeReplyTo) =>
        val baseEffect = Effect
          .persist[SagaEvent, State](List(
            PhaseCompleted("rollback", success),
            TransactionCompleted(false)
          ))
        maybeReplyTo.map(
          baseEffect
            .thenReply[TransactionResponse](_)(_ => TransactionResponse.Failed(state.currentTransaction.get.id, ""))
        ).getOrElse(baseEffect)

      case CompletePhase(phase, success, maybeReplyTo) =>
        Effect.persist(PhaseCompleted(phase, success))
    }
  }


  private def startPreparePhase(context: ActorContext[SagaCommand], replyTo: Option[ActorRef[TransactionResponse]]): State => Unit = { _ =>
    context.self ! StartNextStep(replyTo)
  }

  private def startNextStep(context: ActorContext[SagaCommand], maybeReplyTo: Option[ActorRef[TransactionResponse]])(state: State)(implicit ec: ExecutionContext): Effect[SagaEvent, State] = {
    state.currentTransaction match {
      case Some(transaction) if state.currentPhase.nonEmpty =>

        transaction.steps.find(step =>
          //TODO: what if the step's participant is called, and what if it is not
          step.phase == state.currentPhase && !state.completedSteps.contains(step)
        ) match {
          case Some(step) =>
            Effect
              .persist(TransactionStepStarted(step))
              .thenRun { _ =>
                val future = state.currentPhase match {
                  case "prepare" => step.participant.prepare(transaction.id, Map.empty)
                  case "commit" => step.participant.commit(transaction.id, Map.empty)
                  case "rollback" => step.participant.rollback(transaction.id, Map.empty)
                }
                future.onComplete {
                  case Success(success) => context.self ! CompleteStep(step, success, maybeReplyTo)
                  case Failure(_) => context.self ! CompleteStep(step, success = false, maybeReplyTo)
                }
              }
          case None =>
            // No more steps in this phase, move to the next phase or complete the transaction
            Effect.persist(PhaseCompleted(state.currentPhase, success = true))
              .thenRun(_ => context.self ! CompletePhase(state.currentPhase, success = true, maybeReplyTo))
        }
      case _ =>
        Effect.none
    }
  }

  private def startCommitPhase(context: ActorContext[SagaCommand], state: State, replyTo: Option[ActorRef[TransactionResponse]]): Unit = {
    context.self ! StartNextStep(replyTo)
  }

  private def startRollbackPhase(context: ActorContext[SagaCommand], state: State, replyTo: Option[ActorRef[TransactionResponse]]): Unit = {
    context.self ! StartNextStep(replyTo)
  }

  private def continuePhase(context: ActorContext[SagaCommand], replyTo: Option[ActorRef[TransactionResponse]])(state: State): Unit = {
    context.self ! StartNextStep(replyTo)
  }
}
