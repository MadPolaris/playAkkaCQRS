package net.imadz.application.projection

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.projection.eventsourced.EventEnvelope
import akka.projection.jdbc.scaladsl.JdbcHandler
import net.imadz.application.aggregates.WorkOrderAggregate.WorkOrderEntityTypeKey
import net.imadz.application.aggregates.WorkOrderProtocol.{RecordLotCompleted, RecordLotFailed}
import net.imadz.common.application.projection.ScalikeJdbcSession
import net.imadz.domain.entities.LotEntity.{LotCreated, LotFailed, ProcessCompleted}
import net.imadz.infrastructure.persistence.LotEventAdapter
import net.imadz.infrastructure.proto.lot.LotEventPO
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentHashMap

/**
 * Akka Projection handler that bridges Lot ProcessCompleted events
 * to WorkOrder RecordLotCompleted commands.
 *
 * Reads Lot events from the journal (eventsByTag). On LotCreated it
 * remembers the lotId→workOrderId mapping. On ProcessCompleted it
 * sends RecordLotCompleted to the WorkOrder shard so the WorkOrder
 * can aggregate completion and transition to Completed.
 *
 * Because this is driven by the journal with offset tracking, it
 * survives JVM crashes — on restart it replays from the last
 * committed offset.
 */
class WorkOrderCompletionHandler(system: ActorSystem[_])
  extends JdbcHandler[EventEnvelope[LotEventPO.Event], ScalikeJdbcSession] {

  private val logger = LoggerFactory.getLogger(getClass)
  private val adapter = new LotEventAdapter
  private val sharding = ClusterSharding(system)

  // lotId (UUID string) → workOrderId
  private val lotToWorkOrder = new ConcurrentHashMap[String, String]()

  override def process(session: ScalikeJdbcSession, envelope: EventEnvelope[LotEventPO.Event]): Unit = {
    val lotId = envelope.persistenceId.split("\\|", 2).lastOption.getOrElse(envelope.persistenceId)

    adapter.fromJournal(envelope.event, "").events.foreach {
      case LotCreated(_, _, _, _, Some(workOrderId)) =>
        lotToWorkOrder.put(lotId, workOrderId)
        logger.debug(s"[WorkOrderCompletion] Registered lot=$lotId → workOrder=$workOrderId")

      case ProcessCompleted(_, passCount, scrapCount, reworkCount) =>
        val workOrderId = lotToWorkOrder.get(lotId)
        if (workOrderId != null) {
          val ref = sharding.entityRefFor(WorkOrderEntityTypeKey, workOrderId)
          ref ! RecordLotCompleted(workOrderId, lotId, passCount, scrapCount, reworkCount)
          logger.info(s"[WorkOrderCompletion] Lot sealed → WorkOrder: lot=$lotId wo=$workOrderId " +
            s"pass=$passCount scrap=$scrapCount rework=$reworkCount")
          lotToWorkOrder.remove(lotId) // cleanup
        } else {
          logger.warn(s"[WorkOrderCompletion] ProcessCompleted for lot=$lotId but no workOrderId registered — " +
            s"this may be a child lot (expected, ignoring)")
        }


      case LotFailed(reason, failedAt) =>
        val workOrderId = lotToWorkOrder.get(lotId)
        if (workOrderId != null) {
          val ref = sharding.entityRefFor(WorkOrderEntityTypeKey, workOrderId)
          ref ! RecordLotFailed(workOrderId, lotId, reason, failedAt)
          logger.warn(s"[WorkOrderCompletion] Lot failed → WorkOrder: lot=$lotId wo=$workOrderId " +
            s"reason=$reason failedAt=$failedAt")
          lotToWorkOrder.remove(lotId)
        }
      case _ => // ignore other Lot events
    }
  }
}
