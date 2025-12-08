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
import net.imadz.infra.saga.serialization.{AkkaSerializationWrapper, SagaTransactionCoordinatorEventAdapter, StepExecutorEventAdapter}
import net.imadz.infra.saga.{ForSaga, SagaTransactionCoordinator, StepExecutor}
import net.imadz.infrastructure.persistence.SagaTransactionStepSerializer
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

import scala.concurrent.duration.DurationInt

trait SagaTransactionCoordinatorBootstrap extends ForSaga {

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
    val stepSerializer: SagaTransactionStepSerializer = SagaTransactionStepSerializer(repository = repository, ec = global)

    Behaviors.logMessages(LogOptions().withLogger(LoggerFactory.getLogger("iMadz")).withLevel(Level.INFO),
      Behaviors
        .setup { actorContext =>
          EventSourcedBehavior(
            persistenceId = PersistenceId(entityTypeKey.name, transactionId.toString),
            emptyState = SagaTransactionCoordinator.State.apply(),
            commandHandler = SagaTransactionCoordinator.commandHandler(actorContext, (key, step) =>
              createStepExecutor(actorContext, key, stepSerializer)),
            eventHandler = SagaTransactionCoordinator.eventHandler
          ).withTagger(_ => Set(tag))
            .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 3))
            .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1).withStashCapacity(100))
            .eventAdapter(SagaTransactionCoordinatorEventAdapter(actorContext.system, stepSerializer, global))
        })
  }


  private def createStepExecutor(context: ActorContext[SagaTransactionCoordinator.Command], key: String, stepSerializer: SagaTransactionStepSerializer) = {
    val akkaSerialization = AkkaSerializationWrapper(context.system.classicSystem)
    val global = scala.concurrent.ExecutionContext.global
    val eventAdapter = StepExecutorEventAdapter(akkaSerialization, stepSerializer, global)
    context.spawn(StepExecutor[Any, Any](eventAdapter)(
      PersistenceId.ofUniqueId(key),
      defaultMaxRetries = 5,
      initialRetryDelay = 100.millis,
      circuitBreakerSettings = StepExecutor.CircuitBreakerSettings(5, 30.seconds, 30.seconds)
    ), key)
  }
}
