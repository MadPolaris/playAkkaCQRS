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

import java.util.concurrent.atomic.AtomicBoolean

object FabDemoViewHandler {
  private val pendingReset = new AtomicBoolean(false)

  /** Signal that state should be cleared before processing the next event.
   * Safe to call from any thread — actual clearing happens in the projection thread. */
  def resetAll(): Unit = {
    pendingReset.set(true)
  }
}

class FabDemoViewHandler(publishToUI: FabSimulationEvent => Unit)
  extends JdbcHandler[EventEnvelope[Any], ScalikeJdbcSession] {

  private val logger = LoggerFactory.getLogger(getClass)
  private val adapter = new LotEventAdapter

  // Per-lot view state keyed by lotId (UUID string)
  private val lotStates = mutable.Map.empty[String, LotViewState]
  // Global wafer name registry: UUID string → human-readable name (e.g. "WAFER-1")
  // Populated on LotCreated so child lots can resolve names consistently during Saga TCC transfers
  private val waferRegistry = mutable.Map.empty[String, String]

  override def process(session: ScalikeJdbcSession, envelope: EventEnvelope[Any]): Unit = {
    if (FabDemoViewHandler.pendingReset.compareAndSet(true, false)) {
      lotStates.clear()
      waferRegistry.clear()
    }
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
      case LotCreated(productId, waferNames, parentLotId, splitReason) =>
        // Always create view state — child lots with empty wafers are first-class entities
        // with parent-child relationships, not anonymous containers.
        val nameToUuid: Map[String, String] = waferNames.map { case (id, name) => name -> id.toString }
        val uuidToName: Map[String, String] = waferNames.map { case (id, name) => id.toString -> name }
        waferNames.foreach { case (id, name) => waferRegistry(id.toString) = name }
        lotStates(lotId) = LotViewState(
          lotId = lotId,
          waferCount = waferNames.size,
          uuidToName = mutable.Map.from(uuidToName),
          nameToUuid = mutable.Map.from(nameToUuid),
          parentLotId = parentLotId.map(_.toString),
          splitReason = splitReason.map(sr => splitReasonKey(sr))
        )
        // Register child lot in shared registry for Entity State queries
        (parentLotId, splitReason) match {
          case (Some(pid), Some(sr)) =>
            FabDemoViewProjection.childLotRegistry.put(
              pid.toString + ":" + splitReasonKey(sr), lotId)
          case _ => ()
        }
        publishLotState(lotId)
        // Cascade to parent so its childLots reflect the new child
        parentLotId.foreach(pid => publishLotState(pid.toString))

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

      // Split/grouping events are no-ops here: child lot identity comes from LotCreated.parentLotId+splitReason
      // Wafer movement between lots is handled by WaferRemovalCommitted/WaferAdditionCommitted
      case WafersSplitForRework(_, _, _) | WafersSentAsPilot(_) | WafersSampled(_, _) | WafersHeld(_, _) | WafersReleased(_) => ()

      case WafersReworked(waferIds) =>
        lotStates.get(lotId).foreach { state =>
          waferIds.foreach { name =>
            state.waferReworks(name) = state.waferReworks.getOrElse(name, 0) + 1
          }
          publishLotState(lotId)
        }

      case WaferAdditionReserved(transferId, waferIds) =>
        lotStates.get(lotId).foreach { state =>
          state.pendingIncomingWafers(transferId.toString) = waferIds.map(_.toString)
        }

      case WaferRemovalCommitted(_, waferNames) =>
        lotStates.get(lotId).foreach { state =>
          waferNames.foreach { name =>
            state.nameToUuid.remove(name).foreach { uuid =>
              state.uuidToName.remove(uuid)
            }
            state.waferClassifications.remove(name)
            state.waferReworks.remove(name)
          }
          state.waferCount = state.uuidToName.size
          state.passCount = state.waferClassifications.values.count(_ == "PASS")
          state.scrapCount = state.waferClassifications.values.count(_ == "SCRAP")
          if (state.uuidToName.isEmpty && state.parentLotId.isDefined) {
            state.status = "Sealed"
          }
          publishLotState(lotId)
          state.parentLotId.foreach(pid => publishLotState(pid)) // cascade to parent
        }

      case WaferAdditionCommitted(transferId) =>
        lotStates.get(lotId).foreach { state =>
          state.pendingIncomingWafers.remove(transferId.toString).foreach { waferUuids =>
            waferUuids.foreach { uuid =>
              val name = waferRegistry.getOrElse(uuid, uuid.take(8))
              state.uuidToName(uuid) = name
              state.nameToUuid(name) = uuid
            }
          }
          state.waferCount = state.uuidToName.size
          publishLotState(lotId)
          state.parentLotId.foreach(pid => publishLotState(pid)) // cascade to parent
        }

      case WaferAdditionCanceled(transferId) =>
        lotStates.get(lotId).foreach { state =>
          state.pendingIncomingWafers.remove(transferId.toString)
        }

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
      val displayLotId = state.parentLotId match {
        case Some(parentId) =>
          val suffix = state.splitReason.map(_.toUpperCase).getOrElse("CHILD")
          s"${parentId.take(8)}-$suffix"
        case None => state.lotId.take(8)
      }

      // Child lots are derived from actual child LotViewStates (those whose parentLotId == this lotId)
      val childLots = lotStates.collect {
        case (_, childState) if childState.parentLotId.contains(lotId) =>
          val suffix = childState.splitReason.map(_.toUpperCase).getOrElse("CHILD")
          LotStateSnapshot(
            s"${state.lotId.take(8)}-$suffix",
            childState.status,
            childState.waferCount,
            childState.passCount,
            childState.scrapCount,
            childState.currentArea
          )
      }.toSeq

      val sourceLot = LotStateSnapshot(
        displayLotId,
        state.status, state.waferCount, state.passCount, state.scrapCount, state.currentArea
      )

      val wafers = state.uuidToName.keys.map { uuid =>
        val name = state.uuidToName(uuid)
        val classification = state.waferClassifications.getOrElse(name, "Pending")
        WaferStateSnapshot(
          waferId = name,
          status = if (classification == "SCRAP") "Scrapped"
          else if (classification == "HOLD") "OnHold"
          else "Active",
          lotId = displayLotId,
          classification = classification,
          reworkCount = state.waferReworks.getOrElse(name, 0)
        )
      }.toSeq

      publishToUI(AggregateStateUpdated(sourceLot, childLots, wafers))
    }
  }

  private def splitReasonKey(sr: SplitReason): String = sr match {
    case ReworkSplit => "rwk"
    case ScrapSplit => "scrap"
    case PilotSplit => "pilot"
    case SampleSplit => "sample"
    case HoldSplit => "hold"
  }

  private case class LotViewState(
    lotId: String,
    var status: String = "Active",
    var waferCount: Int = 0,
    var passCount: Int = 0,
    var scrapCount: Int = 0,
    var currentArea: String = "",
    uuidToName: mutable.Map[String, String] = mutable.Map.empty,
    nameToUuid: mutable.Map[String, String] = mutable.Map.empty,
    waferClassifications: mutable.Map[String, String] = mutable.Map.empty,
    waferReworks: mutable.Map[String, Int] = mutable.Map.empty,
    pendingIncomingWafers: mutable.Map[String, Set[String]] = mutable.Map.empty,
    parentLotId: Option[String] = None,
    splitReason: Option[String] = None
  )
}
