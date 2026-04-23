package net.imadz.infra.saga.persistence

import akka.actor.ExtendedActorSystem
import akka.persistence.typed.{EventAdapter, EventSeq}
import net.imadz.common.serialization.SerializationExtension
import net.imadz.infra.saga.SagaTransactionCoordinator
import net.imadz.infra.saga.persistence.converters.SagaCoordinatorProtoConverters
import net.imadz.infra.saga.proto.saga_v2._

import scala.concurrent.ExecutionContext

/**
 * SagaTransactionCoordinatorEventAdapter
 *
 * 实现了 Converter 分离的设计：
 * 1. 混入 SagaCoordinatorProtoConverters 获取所有转换逻辑
 * 2. 注入 ExtendedActorSystem 获取 SerializationExtension
 */
class SagaTransactionCoordinatorEventAdapter(override val system: ExtendedActorSystem)
  extends EventAdapter[SagaTransactionCoordinator.Event, SagaTransactionCoordinatorEventPO]
    with SagaCoordinatorProtoConverters {

  // 如果 Mapper 内部需要 EC (目前不需要，但保留以防万一)
  implicit val ec: ExecutionContext = system.dispatcher

  override def manifest(event: SagaTransactionCoordinator.Event): String = ""

  override def toJournal(e: SagaTransactionCoordinator.Event): SagaTransactionCoordinatorEventPO = {
    val payload = e match {
      case evt: SagaTransactionCoordinator.TransactionStarted =>
        SagaTransactionCoordinatorEventPO.Event.Started(TransactionStartedConv.toProto(evt))

      case evt: SagaTransactionCoordinator.PhaseSucceeded =>
        SagaTransactionCoordinatorEventPO.Event.PhaseSucceeded(PhaseSucceededConv.toProto(evt))

      case evt: SagaTransactionCoordinator.PhaseFailed =>
        SagaTransactionCoordinatorEventPO.Event.PhaseFailed(PhaseFailedConv.toProto(evt))

      case evt: SagaTransactionCoordinator.TransactionCompleted =>
        SagaTransactionCoordinatorEventPO.Event.TransactionCompleted(TransactionCompletedConv.toProto(evt))

      case evt: SagaTransactionCoordinator.TransactionFailed =>
        SagaTransactionCoordinatorEventPO.Event.TransactionFailed(TransactionFailedConv.toProto(evt))
    }
    SagaTransactionCoordinatorEventPO(payload)
  }

  override def fromJournal(p: SagaTransactionCoordinatorEventPO, manifest: String): EventSeq[SagaTransactionCoordinator.Event] = {
    p.event match {
      case SagaTransactionCoordinatorEventPO.Event.Started(po) =>
        EventSeq.single(TransactionStartedConv.fromProto(po))

      case SagaTransactionCoordinatorEventPO.Event.PhaseSucceeded(po) =>
        EventSeq.single(PhaseSucceededConv.fromProto(po))

      case SagaTransactionCoordinatorEventPO.Event.PhaseFailed(po) =>
        EventSeq.single(PhaseFailedConv.fromProto(po))

      case SagaTransactionCoordinatorEventPO.Event.TransactionCompleted(po) =>
        EventSeq.single(TransactionCompletedConv.fromProto(po))

      case SagaTransactionCoordinatorEventPO.Event.TransactionFailed(po) =>
        EventSeq.single(TransactionFailedConv.fromProto(po))

      case _ =>
        EventSeq.empty
    }
  }

}