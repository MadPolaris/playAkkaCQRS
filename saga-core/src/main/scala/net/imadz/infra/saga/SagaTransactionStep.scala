package net.imadz.infra.saga

import net.imadz.infra.saga.SagaPhase.TransactionPhase

import scala.concurrent.duration._

/**
 * Represents a single step in a Saga transaction.
 *
 * @param stepId unique identifier for the step
 * @param phase the phase in which this step is executed
 * @param participant the saga participant that performs the actual operation
 * @param maxRetries maximum number of retries for this step (default: 3)
 * @param timeoutDuration timeout for each execution of this step (default: 5 seconds)
 * @param retryWhenRecoveredOngoing if true, the step will be retried if recovered while in Ongoing status
 * @param stepGroup steps with the same group ID within a phase are executed in parallel
 * @tparam E type of error
 * @tparam R type of result
 * @tparam C type of context
 */
case class SagaTransactionStep[E, R, C](
                                         stepId: String,
                                         phase: TransactionPhase,
                                         participant: SagaParticipant[E, R, C],
                                         maxRetries: Int = 3,
                                         timeoutDuration: FiniteDuration = 5.seconds,
                                         retryWhenRecoveredOngoing: Boolean = false,
                                         stepGroup: Int = 1
                                       )
