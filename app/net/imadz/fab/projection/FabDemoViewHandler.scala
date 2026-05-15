package net.imadz.fab.projection

import akka.projection.eventsourced.EventEnvelope
import akka.projection.jdbc.scaladsl.JdbcHandler
import net.imadz.common.application.projection.ScalikeJdbcSession
import net.imadz.domain.entities.LotEntity._
import net.imadz.fab.events.{AggregateStateUpdated, FabSimulationEvent, LotStateSnapshot, WaferStateSnapshot}
import net.imadz.infrastructure.persistence.LotEventAdapter
import net.imadz.infrastructure.proto.lot.LotEventPO
import org.slf4j.LoggerFactory

import scala.collection.mutable

class FabDemoViewHandler(publishToUI: FabSimulationEvent => Unit)
  extends JdbcHandler[EventEnvelope[Any], ScalikeJdbcSession] {

  private val logger = LoggerFactory.getLogger(getClass)
  private val adapter = new LotEventAdapter

  // Per-lot view state keyed by lotId (UUID string)
  private val lotStates = mutable.Map.empty[String, LotViewState]

  override def process(session: ScalikeJdbcSession, envelope: EventEnvelope[Any]): Unit = {
    envelope.event match {
      case lotEvent: LotEventPO.Event =>
        val lotId = envelope.persistenceId.split("\\|", 2).lastOption.getOrElse(envelope.persistenceId)
        adapter.fromJournal(lotEvent, "").events.foreach { domainEvent =>
          logger.debug(s"[FabDemoView] Processing ${domainEvent.getClass.getSimpleName} for lot=$lotId")
          handleLotEvent(lotId, domainEvent)
        }
      case _ => // ignore non-Lot events (FabSaga, SagaCoordinator — handled by FabDemoEventBridge)
    }
  }

  private def handleLotEvent(lotId: String, event: LotEvent): Unit = {
    event match {
      case LotCreated(_, waferNames) =>
        val nameToUuid: Map[String, String] = waferNames.map { case (id, name) => name -> id.toString }
        val uuidToName: Map[String, String] = waferNames.map { case (id, name) => id.toString -> name }
        lotStates(lotId) = LotViewState(
          lotId = lotId,
          waferCount = waferNames.size,
          uuidToName = mutable.Map.from(uuidToName),
          nameToUuid = mutable.Map.from(nameToUuid)
        )
        publishLotState(lotId)

      case WaferClassified(waferId, classification, reworkCount, _) =>
        lotStates.get(lotId).foreach { state =>
          state.uuidToName.get(waferId.toString).foreach { waferName =>
            state.waferClassifications(waferName) = classification
            state.waferReworks(waferName) = reworkCount
            classification match {
              case "PASS" => state.passCount = state.waferClassifications.values.count(_ == "PASS")
              case "SCRAP" => state.scrapCount = state.waferClassifications.values.count(_ == "SCRAP")
              case _ => ()
            }
          }
          publishLotState(lotId)
        }

      case WafersSplitForRework(reworkWaferIds, scrapWaferIds, _) =>
        lotStates.get(lotId).foreach { state =>
          reworkWaferIds.foreach { name => state.waferSubLots(name) = "rwk" }
          scrapWaferIds.foreach { name => state.waferSubLots(name) = "scrap" }
          publishLotState(lotId)
        }

      case WafersSentAsPilot(waferIds) =>
        lotStates.get(lotId).foreach { state =>
          waferIds.foreach { name => state.waferSubLots(name) = "pilot" }
          publishLotState(lotId)
        }

      case WafersSampled(sampleIds, _) =>
        lotStates.get(lotId).foreach { state =>
          sampleIds.foreach { name => state.waferSubLots(name) = "sample" }
          publishLotState(lotId)
        }

      case WafersHeld(waferIds, _) =>
        lotStates.get(lotId).foreach { state =>
          waferIds.foreach { name => state.waferSubLots(name) = "hold" }
          publishLotState(lotId)
        }

      case WafersReleased(waferIds) =>
        lotStates.get(lotId).foreach { state =>
          waferIds.foreach { name => state.waferSubLots.remove(name) }
          publishLotState(lotId)
        }

      case WafersReworked(waferIds) =>
        lotStates.get(lotId).foreach { state =>
          waferIds.foreach { name =>
            state.waferReworks(name) = state.waferReworks.getOrElse(name, 0) + 1
          }
          publishLotState(lotId)
        }

      case WaferRemovalCommitted(_, waferNames) =>
        lotStates.get(lotId).foreach { state =>
          waferNames.foreach { name => state.waferSubLots.remove(name) }
          publishLotState(lotId)
        }

      case WaferAdditionCommitted(_) =>
        lotStates.get(lotId).foreach { _ => publishLotState(lotId) }

      case LotSealed() =>
        lotStates.get(lotId).foreach { state =>
          state.status = "Sealed"
          publishLotState(lotId)
        }

      case ProcessCompleted(_, passCount, scrapCount, reworkCount) =>
        lotStates.get(lotId).foreach { state =>
          state.status = "Sealed"
          state.passCount = passCount
          state.scrapCount = scrapCount
          publishLotState(lotId)
        }

      case TransportCompleted(_, equipmentId) =>
        lotStates.get(lotId).foreach { state =>
          state.currentArea = equipmentId
          publishLotState(lotId)
        }

      case FoupLoaded(_, stockerId) =>
        lotStates.get(lotId).foreach { state =>
          state.currentArea = stockerId
          publishLotState(lotId)
        }

      case _ => () // PhaseStarted, PhaseCompleted, etc. — no UI state change
    }
  }

  private def publishLotState(lotId: String): Unit = {
    lotStates.get(lotId).foreach { state =>
      val childLots = state.waferSubLots
        .groupBy(_._2)
        .map { case (subLotKey, wafers) =>
          LotStateSnapshot(
            s"${state.lotId}-${subLotKey.toUpperCase}",
            "Active", wafers.size, 0, 0, state.currentArea
          )
        }.toSeq

      val sourceLot = LotStateSnapshot(
        state.lotId.take(8), // truncated UUID for display
        state.status, state.waferCount, state.passCount, state.scrapCount, state.currentArea
      )

      val wafers = state.uuidToName.keys.map { uuid =>
        val name = state.uuidToName(uuid)
        val subLot = state.waferSubLots.get(name)
        val waferLot = subLot.map(k => s"${state.lotId}-${k.toUpperCase}").getOrElse(state.lotId.take(8))
        val classification = state.waferClassifications.getOrElse(name, "Pending")
        WaferStateSnapshot(
          waferId = name,
          status = if (classification == "SCRAP") "Scrapped"
          else if (classification == "HOLD") "OnHold"
          else "Active",
          lotId = waferLot,
          classification = classification,
          reworkCount = state.waferReworks.getOrElse(name, 0)
        )
      }.toSeq

      publishToUI(AggregateStateUpdated(sourceLot, childLots, wafers))
    }
  }

  private case class LotViewState(
    lotId: String,
    var status: String = "Active",
    waferCount: Int = 0,
    var passCount: Int = 0,
    var scrapCount: Int = 0,
    var currentArea: String = "",
    uuidToName: mutable.Map[String, String] = mutable.Map.empty,
    nameToUuid: mutable.Map[String, String] = mutable.Map.empty,
    waferClassifications: mutable.Map[String, String] = mutable.Map.empty,
    waferReworks: mutable.Map[String, Int] = mutable.Map.empty,
    waferSubLots: mutable.Map[String, String] = mutable.Map.empty
  )
}
