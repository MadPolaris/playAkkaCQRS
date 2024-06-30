package net.imadz.infrastructure.bootstrap

import akka.actor.typed._
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityContext, EntityRef}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{EventSourcedBehavior, RetentionCriteria}
import net.imadz.application.aggregates.MoneyTransferTransactionAggregate.{MoneyTransferTransactionCommand, TransactionEntityTypeKey}
import net.imadz.application.aggregates.TransactionAggregate
import net.imadz.application.aggregates.behaviors.MoneyTransferTransactionBehaviors
import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.common.CommonTypes.Id
import net.imadz.common.Id
import net.imadz.common.application.saga.TransactionCoordinator
import net.imadz.domain.entities.TransactionEntity
import net.imadz.domain.entities.behaviors.TransactionEventHandler
import net.imadz.infrastructure.persistence.{TransactionEventAdapter, TransactionSnapshotAdapter}
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

trait TransactionBootstrap {
  def initTransactionAggregate(sharding: ClusterSharding,
    coordinatorRepository: TransactionCoordinatorRepository,
    repository: CreditBalanceRepository)(implicit ec: ExecutionContext, scheduler: Scheduler): Unit = {

    val behaviorFactory: EntityContext[MoneyTransferTransactionCommand] => Behavior[MoneyTransferTransactionCommand] = { context =>
      val i = math.abs(context.entityId.hashCode % TransactionAggregate.tags.size)
      val selectedTag = TransactionAggregate.tags(i)
      apply(Id.of(context.entityId), selectedTag,
        coordinatorRepository.findTransactionCoordinatorByTrxId(Id.of(context.entityId)),
        repository,
        sharding)
    }

    sharding.init(Entity(TransactionEntityTypeKey)(behaviorFactory))
  }

  private def apply(transaction: Id,
    tag: String,
    coordinator: EntityRef[TransactionCoordinator.SagaCommand],
    repository: CreditBalanceRepository,
    sharding: ClusterSharding
  )(implicit ec: ExecutionContext, scheduler: Scheduler): Behavior[MoneyTransferTransactionCommand] =
    Behaviors.logMessages(LogOptions().withLogger(LoggerFactory.getLogger("iMadz")).withLevel(Level.INFO),
      Behaviors
        .setup { actorContext =>
          EventSourcedBehavior(
            persistenceId = PersistenceId(TransactionEntityTypeKey.name, transaction.toString),
            emptyState = TransactionEntity.empty(transaction),
            commandHandler = MoneyTransferTransactionBehaviors.apply(actorContext, coordinator, repository),
            eventHandler = TransactionEventHandler.apply
          ).withTagger(_ => Set(tag))
            .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 3))
            .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1).withStashCapacity(100))
            .eventAdapter(new TransactionEventAdapter)
            .snapshotAdapter(new TransactionSnapshotAdapter)
        })
}
