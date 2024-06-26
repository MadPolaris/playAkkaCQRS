package net.imadz.infrastructure.bootstrap

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{Behavior, LogOptions, SupervisorStrategy}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityContext}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{EventSourcedBehavior, RetentionCriteria}
import net.imadz.application.aggregates.CreditBalanceAggregate
import net.imadz.application.aggregates.TransactionAggregate.{TransactionCommand, TransactionEntityTypeKey}
import net.imadz.application.aggregates.behaviors.TransactionBehaviors
import net.imadz.common.CommonTypes.Id
import net.imadz.common.Id
import net.imadz.domain.entities.TransactionEntity
import net.imadz.domain.entities.behaviors.TransactionEventHandler
import net.imadz.infrastructure.persistence.{TransactionEventAdapter, TransactionSnapshotAdapter}
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

import scala.concurrent.duration.DurationInt

trait TransactionBootstrap {
  def initTransactionAggregate(sharding: ClusterSharding): Unit = {
    val behaviorFactory: EntityContext[TransactionCommand] => Behavior[TransactionCommand] = { context =>
      val i = math.abs(context.entityId.hashCode % CreditBalanceAggregate.tags.size)
      val selectedTag = CreditBalanceAggregate.tags(i)
      apply(Id.of(context.entityId), selectedTag)
    }

    sharding.init(Entity(TransactionEntityTypeKey)(behaviorFactory))
  }

  private def apply(transaction: Id, tag: String): Behavior[TransactionCommand] =
    Behaviors.logMessages(LogOptions().withLogger(LoggerFactory.getLogger("iMadz")).withLevel(Level.INFO),
      Behaviors
        .setup { actorContext =>
          EventSourcedBehavior(
            persistenceId = PersistenceId(TransactionEntityTypeKey.name, transaction.toString),
            emptyState = TransactionEntity.empty(transaction),
            commandHandler = TransactionBehaviors.apply,
            eventHandler = TransactionEventHandler.apply
          ).withTagger(_ => Set(tag))
            .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 3))
            .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1).withStashCapacity(100))
            .eventAdapter(new TransactionEventAdapter)
            .snapshotAdapter(new TransactionSnapshotAdapter)
        })
}
