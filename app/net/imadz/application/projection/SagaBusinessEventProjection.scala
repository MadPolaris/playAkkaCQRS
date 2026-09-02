package net.imadz.application.projection

import akka.actor.typed.ActorSystem
import akka.contrib.persistence.mongodb.MongoReadJournal
import akka.persistence.query.Offset
import akka.persistence.query.scaladsl.{CurrentEventsByPersistenceIdQuery, ReadJournal}
import akka.projection.ProjectionId
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.jdbc.scaladsl.JdbcHandler
import akka.projection.jdbc.scaladsl.JdbcProjection
import akka.projection.scaladsl.{ExactlyOnceProjection, SourceProvider}
import akka.stream.scaladsl.Sink
import com.typesafe.config.ConfigFactory
import net.imadz.common.application.projection.ScalikeJdbcSession
import net.imadz.infra.saga.SagaTransactionCoordinator
import net.imadz.infra.saga.SagaTransactionCoordinator.{StatusSnapshot, TransactionResult}
import net.imadz.infra.saga.dsl.SagaDefinition
import net.imadz.infra.saga.dsl.SagaRegistry
import net.imadz.infra.saga.proto.saga_v3.SagaTransactionCoordinatorEventPO
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

/**
  * onResult wiring (plan §7): reads coordinator terminal events by tag, decodes the
  * TransactionStarted payload (definition ref + args) either from the in-stream cache or
  * by journal lookup, resolves the registered SagaDefinition, evaluates `onResult`, and
  * publishes the resulting SagaBusinessEvents to the classic event stream (application sink).
  *
  * Delivery is at-least-once; the sink side dedupes by transactionId. Args decode failures
  * are logged and skipped — the offset still advances.
  */
object SagaBusinessEventProjection {

  val projectionName = "SagaBusinessEvent"

  def createProjection(system: ActorSystem[_], index: Int,
                       readJournal: ReadJournal with CurrentEventsByPersistenceIdQuery,
                       coordinatorPidPrefix: String): ExactlyOnceProjection[Offset, EventEnvelope[SagaTransactionCoordinatorEventPO]] = {
    val sourceProvider: SourceProvider[Offset, EventEnvelope[SagaTransactionCoordinatorEventPO]] = EventSourcedProvider
      .eventsByTag[SagaTransactionCoordinatorEventPO](system = system,
        readJournalPluginId = MongoReadJournal.Identifier,
        tag = SagaTransactionCoordinator.tags(index))

    JdbcProjection.exactlyOnce(
      projectionId = ProjectionId(projectionName, SagaTransactionCoordinator.tags(index)),
      sourceProvider = sourceProvider,
      sessionFactory = () => new ScalikeJdbcSession(),
      handler = () => new SagaBusinessEventProjectionHandler(system, readJournal, coordinatorPidPrefix)
    )(system)
  }
}

class SagaBusinessEventProjectionHandler(system: ActorSystem[_],
                                         readJournal: ReadJournal with CurrentEventsByPersistenceIdQuery,
                                         coordinatorPidPrefix: String)
    extends JdbcHandler[EventEnvelope[SagaTransactionCoordinatorEventPO], ScalikeJdbcSession] {

  private val log = LoggerFactory.getLogger(getClass)
  private val startedByTx = new java.util.concurrent.ConcurrentHashMap[String, (String, Int, Array[Byte])]()
  private val deliveredTx = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

  override def process(session: ScalikeJdbcSession, envelope: EventEnvelope[SagaTransactionCoordinatorEventPO]): Unit =
    envelope.event match {
      case po: SagaTransactionCoordinatorEventPO =>
        po.event match {
          case SagaTransactionCoordinatorEventPO.Event.Started(s) =>
            startedByTx.put(s.transactionId, (s.definitionName, s.definitionVersion, s.args.toByteArray))
          case SagaTransactionCoordinatorEventPO.Event.TransactionCompleted(c) =>
            emitBusinessEvents(c.transactionId, successful = true, "")
          case SagaTransactionCoordinatorEventPO.Event.TransactionFailed(f) =>
            emitBusinessEvents(f.transactionId, successful = false, f.reason)
          case _ => ()
        }
      case other => log.debug("Skipping non-saga_v3 payload: {}", other.getClass.getName)
    }

  private def emitBusinessEvents(transactionId: String, successful: Boolean, reason: String): Unit =
    if (deliveredTx.add(transactionId)) { // at-least-once dedupe at the sink boundary
      val meta = Option(startedByTx.remove(transactionId)).orElse(lookupStarted(transactionId))
      meta match {
        case Some((definitionName, definitionVersion, argsBytes)) =>
          SagaRegistry.resolve(definitionName, definitionVersion) match {
            case Success(definition) =>
              val defAny = definition.asInstanceOf[SagaDefinition[Any, Any, Any]]
              defAny.argsCodec.decode(argsBytes) match {
                case Success(args) =>
                  val snapshot = StatusSnapshot(
                    transactionId = transactionId, definitionName = definitionName, definitionVersion = definitionVersion,
                    traceId = "", status = if (successful) "Completed" else "Failed", currentPhase = "",
                    currentStepGroup = 0, isPaused = false, singleStep = false,
                    failReason = if (reason.isEmpty) None else Some(reason), steps = Nil)
                  defAny.onResult(args, TransactionResult(successful, snapshot, reason))
                    .foreach(system.classicSystem.eventStream.publish)
                case Failure(ex) =>
                  log.warn("Saga {}:{} args decode failed for tx {}: {} — business events skipped, offset advances",
                    definitionName, definitionVersion, transactionId, ex.getMessage)
              }
            case Failure(ex) =>
              log.warn("Saga definition {}:{} not registered for tx {}: {} — business events skipped, offset advances",
                definitionName, definitionVersion, transactionId, ex.getMessage)
          }
        case None =>
          log.warn("Terminal event for tx {} without a TransactionStarted payload (journal pruned?) — business events skipped", transactionId)
      }
    }

  /** Offset-restart fallback: the TransactionStarted envelope may have been consumed in a
    * previous incarnation — recover it straight from the journal. */
  private def lookupStarted(transactionId: String): Option[(String, Int, Array[Byte])] =
    try {
      import scala.concurrent.Await
      implicit val mat: akka.stream.Materializer = akka.stream.Materializer(system)
      val pid = s"$coordinatorPidPrefix$transactionId"
      val envs = Await.result(
        readJournal.currentEventsByPersistenceId(pid, 0, Long.MaxValue).collect {
          case e if e.event.isInstanceOf[SagaTransactionCoordinatorEventPO] &&
            e.event.asInstanceOf[SagaTransactionCoordinatorEventPO].event.isStarted => e
        }.runWith(Sink.seq), 10.seconds)
      envs.headOption.flatMap { env =>
        env.event.asInstanceOf[SagaTransactionCoordinatorEventPO].event match {
          case SagaTransactionCoordinatorEventPO.Event.Started(s) =>
            Some((s.definitionName, s.definitionVersion, s.args.toByteArray))
          case _ => None
        }
      }
    } catch {
      case NonFatal(ex) =>
        log.warn("Journal lookup of TransactionStarted for tx {} failed: {}", transactionId, ex.getMessage)
        None
    }
}
