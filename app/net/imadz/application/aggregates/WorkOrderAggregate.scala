package net.imadz.application.aggregates

import akka.actor.typed.{Behavior, SupervisorStrategy}
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import net.imadz.application.aggregates.WorkOrderProtocol._
import net.imadz.application.aggregates.behaviors.WorkOrderBehaviors
import net.imadz.domain.entities.WorkOrderEntity
import net.imadz.infrastructure.persistence.WorkOrderEventAdapter

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object WorkOrderAggregate {

  val WorkOrderEntityTypeKey: EntityTypeKey[WorkOrderCommand] = EntityTypeKey("WorkOrder")
  val tags: Vector[String] = Vector.tabulate(2)(i => s"workorder-$i")

  def apply(workOrderId: String, pipelineStarter: PipelineStarter): Behavior[WorkOrderCommand] =
    Behaviors.setup { actorContext =>
      implicit val ec: ExecutionContext = actorContext.executionContext

      EventSourcedBehavior[WorkOrderCommand, WorkOrderEntity.WorkOrderEvent, WorkOrderEntity.WorkOrderState](
        persistenceId = PersistenceId(WorkOrderEntityTypeKey.name, workOrderId),
        emptyState = WorkOrderEntity.empty,
        commandHandler = WorkOrderBehaviors.apply(workOrderId, pipelineStarter, actorContext),
        eventHandler = WorkOrderEntity.handleEvent
      ).withTagger(_ => {
          val i = math.abs(workOrderId.hashCode % tags.size)
          Set(tags(i))
        })
        .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 50, keepNSnapshots = 3))
        .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1))
        .eventAdapter(new WorkOrderEventAdapter)
        .receiveSignal {
          case (state: WorkOrderEntity.Executing, RecoveryCompleted) =>
            actorContext.log.info(
              s"WorkOrder ${state.workOrderId} recovered in Executing state. " +
              s"product=${state.productId}, wafers=${state.waferIds.size}. Re-running pipeline.")
            actorContext.pipeToSelf(
              pipelineStarter(state.workOrderId, state.productId, state.waferIds, _ => ())
            ) {
              case Success((pass, scrap, rework)) =>
                PipelineCompleted(pass, scrap, rework)
              case Failure(err) =>
                PipelineFailed(err.getMessage)
            }

          case _ => ()
        }
    }

  def init(sharding: ClusterSharding, pipelineStarter: PipelineStarter): Unit = {
    sharding.init(
      Entity(WorkOrderEntityTypeKey) { entityContext =>
        apply(entityContext.entityId, pipelineStarter)
      }
    )
  }
}
