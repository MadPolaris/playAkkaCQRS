package net.imadz.infrastructure.bootstrap

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{Behavior, LogOptions, SupervisorStrategy}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityContext}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{EventSourcedBehavior, RetentionCriteria}
import net.imadz.application.aggregates.CreditBalanceAggregate
import net.imadz.application.aggregates.CreditBalanceAggregate.{CreditBalanceCommand, CreditBalanceEntityTypeKey}
import net.imadz.application.aggregates.behaviors.CreditBalanceBehaviors
import net.imadz.common.CommonTypes.Id
import net.imadz.common.Id
import net.imadz.domain.entities.CreditBalanceEntity
import net.imadz.domain.entities.behaviors.CreditBalanceEventHandler
import net.imadz.infrastructure.persistence.{CreditBalanceEventAdapter, CreditBalanceSnapshotAdapter}
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

import scala.concurrent.duration.DurationInt

trait CreditBalanceBootstrap {

  def initCreditBalanceAggregate(sharding: ClusterSharding): Unit = {
    val behaviorFactory: EntityContext[CreditBalanceCommand] => Behavior[CreditBalanceCommand] = { context =>
      val i = math.abs(context.entityId.hashCode % CreditBalanceAggregate.tags.size)
      val selectedTag = CreditBalanceAggregate.tags(i)
      apply(Id.of(context.entityId), selectedTag)
    }

    sharding.init(Entity(CreditBalanceAggregate.CreditBalanceEntityTypeKey)(behaviorFactory))
  }

  private def apply(userId: Id, tag: String): Behavior[CreditBalanceCommand] =
    Behaviors.logMessages(LogOptions().withLogger(LoggerFactory.getLogger("iMadz")).withLevel(Level.INFO),
      Behaviors
        .setup { actorContext =>
          EventSourcedBehavior(
            persistenceId = PersistenceId(CreditBalanceEntityTypeKey.name, userId.toString),
            emptyState = CreditBalanceEntity.empty(userId),
            commandHandler = CreditBalanceBehaviors.apply,
            eventHandler = CreditBalanceEventHandler.apply
          ).withTagger(_ => Set(tag))
            .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 3))
            .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1).withStashCapacity(100))
            .eventAdapter(new CreditBalanceEventAdapter)
            .snapshotAdapter(new CreditBalanceSnapshotAdapter)
        })
}
