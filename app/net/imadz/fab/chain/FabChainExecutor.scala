package net.imadz.fab.chain

import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import net.imadz.common.CborSerializable
import net.imadz.domain.entities.FabDomainEventEnvelope
import net.imadz.fab.chain.FabDemoPipeline.{FabDemoContext, FabDemoState}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
 * EventSourced executor wrapping [[FabDemoPipeline.runPipeline]].
 *
 * Follows the M2.5+ ChainExecutionActor pattern:
 *   Idle → accept StartChain → persist ChainStarted → run pipeline Future
 *   → on completion persist ChainCompleted
 *   → on failure persist ChainFailed
 *
 * WebSocket events are published by the pipeline stages internally;
 * the executor only tracks chain lifecycle in the journal.
 */
object FabChainExecutor {

  // --- Commands ---
  sealed trait Command extends CborSerializable
  case class StartChain(replyTo: ActorRef[ChainResult]) extends Command
  private case class PipelineCompleted(state: FabDemoState) extends Command
  private case class PipelineFailed(error: String) extends Command

  // --- Events ---
  sealed trait Event extends CborSerializable
  case class ChainStarted(batchId: String, scenarioId: String, waferCount: Int) extends Event
  case class ChainCompleted(batchId: String, passCount: Int, scrapCount: Int, reworkCount: Int) extends Event
  case class ChainFailed(batchId: String, error: String) extends Event

  // --- State ---
  sealed trait ChainState extends CborSerializable {
    def batchId: Option[String]
  }
  case object Idle extends ChainState { val batchId: Option[String] = None }
  case class Running(override val batchId: Option[String]) extends ChainState
  case class Finished(override val batchId: Option[String], passCount: Int, scrapCount: Int, reworkCount: Int) extends ChainState
  case class Failed(override val batchId: Option[String], error: String) extends ChainState

  // --- Reply ---
  case class ChainResult(success: Boolean, message: String) extends CborSerializable

  // --- Factory ---
  def apply(
    persistenceId: String,
    initialState: FabDemoState,
    context: FabDemoContext,
    pipelineFn: (FabDemoState, FabDemoContext) => Future[FabDemoState] = FabDemoPipeline.runPipeline
  ): Behavior[Command] = Behaviors.setup { actorContext =>
    implicit val ec: scala.concurrent.ExecutionContext = actorContext.executionContext
    val batchId = java.util.UUID.randomUUID().toString.take(8)

    EventSourcedBehavior[Command, Event, ChainState](
      persistenceId = PersistenceId("FabChain", persistenceId),
      emptyState = Idle,
      commandHandler = (state, cmd) => handleCommand(state, cmd, batchId, initialState, context, pipelineFn, actorContext),
      eventHandler = (state, event) => handleEvent(state, event)
    ).withRetention(RetentionCriteria.snapshotEvery(20, 2))
     .receiveSignal {
       case (state: Running, RecoveryCompleted) =>
         actorContext.log.info(
           s"FabChainExecutor recovered in Running state for batch ${state.batchId}, re-triggering pipeline")
         implicit val recoveryEc: scala.concurrent.ExecutionContext = actorContext.executionContext
         actorContext.pipeToSelf(pipelineFn(initialState, context)) {
           case Success(finalState) => PipelineCompleted(finalState)
           case Failure(err)       => PipelineFailed(err.getMessage)
         }
       case _ => ()
     }
     .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1))
  }

  private def handleCommand(
    state: ChainState, cmd: Command, batchId: String,
    initState: FabDemoState, ctx: FabDemoContext,
    pipelineFn: (FabDemoState, FabDemoContext) => Future[FabDemoState],
    actorContext: ActorContext[Command]
  ): Effect[Event, ChainState] = state match {
    case Idle =>
      cmd match {
        case StartChain(replyTo) =>
          val event = ChainStarted(batchId, ctx.scenario.scenarioId, ctx.scenario.lotSize)
          Effect.persist(event).thenRun { _ =>
            actorContext.system.eventStream ! EventStream.Publish(
              FabDomainEventEnvelope("Chain", batchId, event))
            replyTo ! ChainResult(success = true, s"Chain $batchId started")
            // Run pipeline asynchronously; pipe result back to self
            implicit val ec: scala.concurrent.ExecutionContext = actorContext.executionContext
            actorContext.pipeToSelf(pipelineFn(initState, ctx)) {
              case Success(finalState) => PipelineCompleted(finalState)
              case Failure(err)       => PipelineFailed(err.getMessage)
            }
          }

        case _ => Effect.unhandled
      }

    case Running(_) =>
      cmd match {
        case PipelineCompleted(fs) =>
          val rework = fs.wafers.values.count(_.reworkCount > 0)
          val event = ChainCompleted(batchId, fs.passCount, fs.scrapCount, rework)
          Effect.persist(event).thenRun { _ =>
            actorContext.system.eventStream ! EventStream.Publish(
              FabDomainEventEnvelope("Chain", batchId, event))
          }
        case PipelineFailed(err) =>
          val event = ChainFailed(batchId, err)
          Effect.persist(event).thenRun { _ =>
            actorContext.system.eventStream ! EventStream.Publish(
              FabDomainEventEnvelope("Chain", batchId, event))
          }
        case _ => Effect.unhandled
      }

    case _ : Finished => Effect.unhandled
    case _ : Failed   => Effect.unhandled
  }

  private def handleEvent(state: ChainState, event: Event): ChainState = event match {
    case ChainStarted(batchId, _, _) => Running(Some(batchId))
    case ChainCompleted(batchId, pass, scrap, rework) => Finished(Some(batchId), pass, scrap, rework)
    case ChainFailed(batchId, error) => Failed(Some(batchId), error)
  }
}
