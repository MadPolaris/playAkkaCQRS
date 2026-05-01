package net.imadz.infra.saga.persistence

import akka.actor.ExtendedActorSystem
import akka.persistence.typed.{EventAdapter, EventSeq}
import net.imadz.infra.saga.StepExecutor._
import net.imadz.infra.saga.proto.saga_v2.StepExecutorEventPO.Event.{Failed, Rescheduled, Started, Succeed}
import net.imadz.infra.saga.proto.saga_v2._
import net.imadz.infra.saga.serialization.SagaExecutorConverter

/**
 * StepExecutorEventAdapter
 * * Responsibilities:
 * 1. Domain Event <-> Proto conversion
 * 2. Mixes in SagaStepProtoMapper to reuse Step conversion logic
 * 3. Uses Akka Serialization to handle generic Result
 * 4. Uses SerializationExtension to handle Participant polymorphism
 */
class StepExecutorEventAdapter(override val system: ExtendedActorSystem)
  extends EventAdapter[Event, StepExecutorEventPO]
    with SagaExecutorConverter {


  // 2. Get Akka's native serialization entry point (used for Result serialization)
  override def manifest(event: Event): String = event.getClass.getCanonicalName

  override def toJournal(e: Event): StepExecutorEventPO = {
    val payload: StepExecutorEventPO.Event = e match {

      case e@ExecutionStarted(_, _, _, _) =>
        Started(ExecutionStartedConv.toProto(e))

      case event: OperationSucceeded[_] =>
        Succeed(OperationSucceededConv.toProto(event))

      case event: ManualFixCompleted[_] =>
        StepExecutorEventPO.Event.ManualFixed(ManualFixCompletedConv.toProto(event))

      case err: OperationFailed =>
        Failed(FailedConv.toProto(err))

      case r: RetryScheduled =>
        Rescheduled(RetryScheduledConv.toProto(r))
    }

    StepExecutorEventPO(payload)
  }

  override def fromJournal(p: StepExecutorEventPO, manifest: String): EventSeq[Event] = {
    p.event match {
      case Started(po) =>
        EventSeq.single(ExecutionStartedConv.fromProto(po))
      case Succeed(po) =>
        EventSeq.single(OperationSucceededConv.fromProto(po))
      case StepExecutorEventPO.Event.ManualFixed(po) =>
        EventSeq.single(ManualFixCompletedConv.fromProto(po))
      case Failed(po) =>
        EventSeq.single(FailedConv.fromProto(po))
      case Rescheduled(po) =>
        EventSeq.single(RetryScheduledConv.fromProto(po))
      case _ =>
        EventSeq.empty
    }
  }


}

