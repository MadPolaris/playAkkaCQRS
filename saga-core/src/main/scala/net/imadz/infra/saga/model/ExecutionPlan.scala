package net.imadz.infra.saga.model

import net.imadz.infra.saga.SagaParticipant.RetryableOrNotException
import net.imadz.infra.saga.SagaPhase._
import net.imadz.infra.saga.SagaTransactionStep

/**
 * Encapsulates the static structure of a Saga transaction.
 * Pre-calculates step groups to simplify navigation during execution.
 */
case class ExecutionPlan(steps: List[SagaTransactionStep[_, _, _]]) {
  
  private val stepsByPhase: Map[TransactionPhase, List[SagaTransactionStep[_, _, _]]] = 
    steps.groupBy(_.phase)
    
  private val groupsByPhase: Map[TransactionPhase, Map[Int, List[SagaTransactionStep[_, _, _]]]] = 
    stepsByPhase.map { case (phase, phaseSteps) =>
      phase -> phaseSteps.groupBy(_.stepGroup)
    }

  def getSteps(phase: TransactionPhase, group: Int): List[SagaTransactionStep[_, _, _]] =
    groupsByPhase.get(phase).flatMap(_.get(group)).getOrElse(Nil)

  def firstGroupInPhase(phase: TransactionPhase): Int = {
    val groups = groupsByPhase.get(phase).map(_.keys.toList.sorted).getOrElse(Nil)
    if (phase == CompensatePhase) groups.lastOption.getOrElse(1)
    else groups.headOption.getOrElse(1)
  }

  def nextGroup(phase: TransactionPhase, currentGroup: Int): Option[Int] = {
    val groups = groupsByPhase.get(phase).map(_.keys.toList.sorted).getOrElse(Nil)
    if (phase == CompensatePhase) {
      groups.reverse.find(_ < currentGroup)
    } else {
      groups.find(_ > currentGroup)
    }
  }

  def isLastGroup(phase: TransactionPhase, currentGroup: Int): Boolean = 
    nextGroup(phase, currentGroup).isEmpty

  def nextPhase(currentPhase: TransactionPhase): Option[TransactionPhase] = currentPhase match {
    case PreparePhase => Some(CommitPhase)
    case CommitPhase => None
    case CompensatePhase => None
  }
}

/**
 * Tracks the dynamic progress of a Saga transaction.
 * Encapsulates state transitions related to step completions and group/phase boundaries.
 */
case class ExecutionProgress(
                              currentPhase: TransactionPhase = PreparePhase,
                              currentStepGroup: Int = 1,
                              pendingSteps: Set[String] = Set.empty,
                              phaseResults: Map[String, Either[RetryableOrNotException, Any]] = Map.empty
                            ) {

  def withStepStarted(stepIds: Set[String]): ExecutionProgress =
    copy(pendingSteps = stepIds, phaseResults = Map.empty)

  def withStepResult(stepId: String, result: Either[RetryableOrNotException, Any]): ExecutionProgress =
    copy(
      pendingSteps = pendingSteps - stepId,
      phaseResults = phaseResults + (stepId -> result)
    )

  def isGroupComplete: Boolean = pendingSteps.isEmpty

  def hasFailure: Boolean = phaseResults.values.exists(_.isLeft)

  def firstError: Option[RetryableOrNotException] = 
    phaseResults.values.collectFirst { case Left(err) => err }
}
