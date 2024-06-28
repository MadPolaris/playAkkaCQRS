package net.imadz.infrastructure.bootstrap

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{Behavior, LogOptions, SupervisorStrategy}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityContext}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{EventSourcedBehavior, RetentionCriteria}
import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.common.CommonTypes.Id
import net.imadz.common.Id
import net.imadz.common.application.saga.TransactionCoordinator
import net.imadz.common.application.saga.TransactionCoordinator.entityTypeKey
import net.imadz.infrastructure.persistence.{TransactionCoordinatorEventAdapter, TransactionCoordinatorSnapshotAdapter}
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt

trait SagaTransactionCoordinatorBootstrap {

  import scala.concurrent.ExecutionContext.Implicits.global

  def initSagaTransactionCoordinatorAggregate(sharding: ClusterSharding, repository: CreditBalanceRepository): Unit = {
    val behaviorFactory: EntityContext[TransactionCoordinator.SagaCommand] => Behavior[TransactionCoordinator.SagaCommand] = { context =>
      val i = math.abs(context.entityId.hashCode % TransactionCoordinator.tags.size)
      val selectedTag = TransactionCoordinator.tags(i)
      apply(Id.of(context.entityId), selectedTag, repository)
    }

    sharding.init(Entity(entityTypeKey)(behaviorFactory))
  }

  //TODO: Bad Smell
  private def apply(transactionId: Id, tag: String, repository: CreditBalanceRepository): Behavior[TransactionCoordinator.SagaCommand] =
    Behaviors.logMessages(LogOptions().withLogger(LoggerFactory.getLogger("iMadz")).withLevel(Level.INFO),
      Behaviors
        .setup { actorContext =>
          EventSourcedBehavior(
            persistenceId = PersistenceId(entityTypeKey.name, transactionId.toString),
            emptyState = TransactionCoordinator.State.apply(),
            commandHandler = TransactionCoordinator.commandHandler(actorContext),
            eventHandler = TransactionCoordinator.eventHandler
          ).withTagger(_ => Set(tag))
            .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 3))
            .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1).withStashCapacity(100))
            .eventAdapter(TransactionCoordinatorEventAdapter(repository, global))
            .snapshotAdapter(TransactionCoordinatorSnapshotAdapter(repository, global))
        })
}
