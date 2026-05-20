package net.imadz.application.projection

import akka.actor.typed.ActorSystem
import akka.contrib.persistence.mongodb.MongoReadJournal
import akka.persistence.query.Offset
import akka.projection.ProjectionId
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.jdbc.scaladsl.JdbcHandler
import akka.projection.jdbc.scaladsl.JdbcProjection
import akka.projection.scaladsl.{ExactlyOnceProjection, SourceProvider}
import net.imadz.common.application.projection.{ProjectionSourceHelpers, ScalikeJdbcSession}
import net.imadz.application.chain.FabPipelineExecutionActor
import net.imadz.application.chain.FabPipelineExecutionActor._
import net.imadz.domain.events.{FabSimulationEvent, GlobalStatusChanged, PipelineTimelineSnapshot}
import org.slf4j.LoggerFactory

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext

/**
 * Akka Projection that converts FabPipelineExecutionActor domain events
 * into WebSocket UI events (PipelineTimelineSnapshot, GlobalStatusChanged).
 *
 * Separates presentation concerns from the domain aggregate: the Actor
 * persists domain facts; this Projection computes UI snapshots from them.
 */
object FabPipelineProjection {

  val ProjectionName = "FabPipeline"
  val Tag = FabPipelineExecutionActor.Tag

  def createProjection(
    system: ActorSystem[_],
    publishToUI: FabSimulationEvent => Unit
  ): ExactlyOnceProjection[Offset, EventEnvelope[Any]] = {
    implicit val ec: ExecutionContext = system.executionContext

    val rawProvider: SourceProvider[Offset, EventEnvelope[Any]] = EventSourcedProvider
      .eventsByTag[Any](
        system = system,
        readJournalPluginId = MongoReadJournal.Identifier,
        tag = Tag
      )

    JdbcProjection.exactlyOnce(
      projectionId = ProjectionId(ProjectionName, Tag),
      sourceProvider = ProjectionSourceHelpers.withIdleTimeout(rawProvider),
      sessionFactory = () => new ScalikeJdbcSession(),
      handler = () => new FabPipelineProjectionHandler(publishToUI)
    )(system)
  }
}

class FabPipelineProjectionHandler(publishToUI: FabSimulationEvent => Unit)
  extends JdbcHandler[EventEnvelope[Any], ScalikeJdbcSession] {

  private val logger = LoggerFactory.getLogger(getClass)

  // Per-workOrder view state
  private case class PipelineViewState(
    workOrderId: String,
    totalPhases: Int,
    completedPhases: Int = 0,
    currentPhase: Option[String] = None
  )

  private val states = TrieMap.empty[String, PipelineViewState]

  /** Extract workOrderId from persistenceId ("FabPipelineExecution|workOrderId"). */
  private def extractWorkOrderId(persistenceId: String): String =
    persistenceId.split("\\|", 2).lastOption.getOrElse(persistenceId)

  override def process(session: ScalikeJdbcSession, envelope: EventEnvelope[Any]): Unit = {
    val woId = extractWorkOrderId(envelope.persistenceId)

    envelope.event match {
      case s: Started =>
        states.put(woId, PipelineViewState(woId, s.stageCount))
        publishToUI(PipelineTimelineSnapshot(
          workOrderId = woId,
          totalPhases = s.stageCount,
          completedPhases = 0,
          currentPhase = None,
          currentPhaseIndex = -1,
          failedPhases = Seq.empty,
          recoveredPhases = Seq.empty,
          ocapTriggers = 0
        ))

      case pd: PhaseDone =>
        states.get(woId) match {
          case Some(vs) =>
            val newCount = vs.completedPhases + 1
            val updated = vs.copy(completedPhases = newCount, currentPhase = Some(pd.phase))
            states.put(woId, updated)
            publishToUI(PipelineTimelineSnapshot(
              workOrderId = woId,
              totalPhases = vs.totalPhases,
              completedPhases = newCount,
              currentPhase = Some(pd.phase),
              currentPhaseIndex = newCount - 1,
              failedPhases = Seq.empty,
              recoveredPhases = Seq.empty,
              ocapTriggers = 0
            ))
          case None =>
            logger.debug(s"PhaseDone for unknown workOrder: $woId")
        }

      case _: AllCompleted =>
        states.get(woId).foreach { vs =>
          publishToUI(PipelineTimelineSnapshot(
            workOrderId = woId,
            totalPhases = vs.totalPhases,
            completedPhases = vs.totalPhases,
            currentPhase = None,
            currentPhaseIndex = vs.totalPhases,
            failedPhases = Seq.empty,
            recoveredPhases = Seq.empty,
            ocapTriggers = 0
          ))
          states.remove(woId)
        }

      case ef: ExecutionFailed =>
        publishToUI(GlobalStatusChanged("FAILED", s"${ef.phase}: ${ef.reason}", "PhaseFailed"))

      case _ => // ignore other events
    }
  }
}
