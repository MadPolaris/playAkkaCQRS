package net.imadz.infra.saga.persistence

import akka.actor.ExtendedActorSystem
import akka.persistence.typed.{EventAdapter, EventSeq}
import net.imadz.infra.saga.StepExecutor._
import net.imadz.infra.saga.proto.saga_v2.StepExecutorEventPO.Event.{Failed, Rescheduled, Started, Succeed}
import net.imadz.infra.saga.proto.saga_v2._
import net.imadz.infra.saga.serialization.SagaExecutorConverter

/**
 * StepExecutorEventAdapter
 * * 职责：
 * 1. 领域事件 <-> Proto 转换
 * 2. 混入 SagaStepProtoMapper 复用 Step 转换逻辑
 * 3. 使用 Akka Serialization 处理泛型 Result
 * 4. 使用 SerializationExtension 处理 Participant 多态
 */
class ExecutorExecutorEventAdapter(override val system: ExtendedActorSystem)
  extends EventAdapter[Event, StepExecutorEventPO]
    with SagaExecutorConverter {


  // 2. 获取 Akka 原生序列化入口 (用于 Result 序列化)
  override def manifest(event: Event): String = event.getClass.getCanonicalName

  override def toJournal(e: Event): StepExecutorEventPO = {
    val payload: StepExecutorEventPO.Event = e match {

      case e@ExecutionStarted(_, _, _) =>
        Started(ExecutionStartedConv.toProto(e))

      case event@OperationSucceeded(_) =>
        Succeed(OperationSucceededConv.toProto(event))

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
      case Failed(po) =>
        EventSeq.single(FailedConv.fromProto(po))
      case Rescheduled(po) =>
        EventSeq.single(RetryScheduledConv.fromProto(po))
      case _ =>
        EventSeq.empty
    }
  }


}

