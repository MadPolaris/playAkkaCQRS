package net.imadz.application.aggregates

import akka.actor.typed.{Behavior, SupervisorStrategy}
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import net.imadz.application.aggregates.WorkOrderProtocol._
import net.imadz.application.aggregates.behaviors.WorkOrderBehaviors
import net.imadz.domain.entities.WorkOrderEntity
import net.imadz.infrastructure.persistence.WorkOrderEventAdapter

import scala.concurrent.duration._

object WorkOrderAggregate {

  val WorkOrderEntityTypeKey: EntityTypeKey[WorkOrderCommand] = EntityTypeKey("WorkOrder")
  val tags: Vector[String] = Vector.tabulate(1)(i => s"workorder-$i")

  def apply(workOrderId: String): Behavior[WorkOrderCommand] =
    Behaviors.setup { actorContext =>
      EventSourcedBehavior[WorkOrderCommand, WorkOrderEntity.WorkOrderEvent, WorkOrderEntity.WorkOrderState](
        persistenceId = PersistenceId(WorkOrderEntityTypeKey.name, workOrderId),
        emptyState = WorkOrderEntity.empty,
        commandHandler = WorkOrderBehaviors.apply(workOrderId, actorContext),
        eventHandler = WorkOrderEntity.handleEvent
      ).withTagger(_ => {
          val i = math.abs(workOrderId.hashCode % tags.size)
          Set(tags(i))
        })
        .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 50, keepNSnapshots = 3))
        .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1))
        .eventAdapter(new WorkOrderEventAdapter)
    }

  def init(sharding: ClusterSharding): Unit = {
    sharding.init(
      Entity(WorkOrderEntityTypeKey) { entityContext =>
        apply(entityContext.entityId)
      }
    )
  }
}
