package net.imadz.infrastructure.bootstrap

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{Behavior, LogOptions, SupervisorStrategy}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityContext}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{EventSourcedBehavior, RetentionCriteria}
import akka.util.Timeout
import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.common.CommonTypes.Id
import net.imadz.common.Id
import net.imadz.infra.saga.SagaTransactionCoordinator.entityTypeKey
import net.imadz.infra.saga.persistence.SagaTransactionCoordinatorEventAdapter
import net.imadz.infra.saga.{SagaTransactionCoordinator, StepExecutor}
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

import scala.concurrent.duration.DurationInt

trait SagaTransactionCoordinatorBootstrap {

  import scala.concurrent.ExecutionContext.Implicits.global

  def initSagaTransactionCoordinatorAggregate(sharding: ClusterSharding, repository: CreditBalanceRepository): Unit = {
    val behaviorFactory: EntityContext[SagaTransactionCoordinator.Command] => Behavior[SagaTransactionCoordinator.Command] = { context =>
      val i = math.abs(context.entityId.hashCode % SagaTransactionCoordinator.tags.size)
      val selectedTag = SagaTransactionCoordinator.tags(i)
      apply(Id.of(context.entityId), selectedTag, repository)
    }

    sharding.init(Entity(SagaTransactionCoordinator.entityTypeKey)(behaviorFactory))
  }

  //TODO: Bad Smell
  private def apply(transactionId: Id, tag: String, repository: CreditBalanceRepository): Behavior[SagaTransactionCoordinator.Command] = {
    implicit val askTimeout: Timeout = Timeout(30.seconds)
    Behaviors.logMessages(LogOptions().withLogger(LoggerFactory.getLogger("iMadz")).withLevel(Level.INFO),
      Behaviors
        .setup { actorContext =>
          EventSourcedBehavior(
            persistenceId = PersistenceId(entityTypeKey.name, transactionId.toString),
            emptyState = SagaTransactionCoordinator.State.apply(),
            commandHandler = SagaTransactionCoordinator.commandHandler(actorContext, (key, step) => createStepExecutor(actorContext, key, repository)),
            eventHandler = SagaTransactionCoordinator.eventHandler
          ).withTagger(_ => Set(tag))
            .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 3))
            .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1).withStashCapacity(100))
            .eventAdapter(SagaTransactionCoordinatorEventAdapter(actorContext.system, repository, global))
        })
  }


  private def createStepExecutor(context: ActorContext[SagaTransactionCoordinator.Command], key: String, creditBalanceRepository: CreditBalanceRepository) = {
    context.spawn(StepExecutor[Any, Any](creditBalanceRepository)(
      PersistenceId.ofUniqueId(key),
      defaultMaxRetries = 5,
      initialRetryDelay = 100.millis,
      circuitBreakerSettings = StepExecutor.CircuitBreakerSettings(5, 30.seconds, 30.seconds)
    ), key)
  }
}
