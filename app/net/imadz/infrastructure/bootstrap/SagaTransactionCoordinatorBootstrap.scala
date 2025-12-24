package net.imadz.infrastructure.bootstrap

import akka.actor.ExtendedActorSystem
import akka.actor.typed._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityContext}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{EventSourcedBehavior, RetentionCriteria}
import akka.util.Timeout
import net.imadz.common.CommonTypes.Id
import net.imadz.common.Id
import net.imadz.infra.saga.SagaTransactionCoordinator.entityTypeKey
import net.imadz.infra.saga.persistence.SagaTransactionCoordinatorEventAdapter
import net.imadz.infra.saga.{ForSaga, SagaTransactionCoordinator, StepExecutor}
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

import scala.concurrent.duration.DurationInt

trait SagaTransactionCoordinatorBootstrap extends ForSaga {

  import scala.concurrent.ExecutionContext.Implicits.global

  def initSagaTransactionCoordinatorAggregate(sharding: ClusterSharding, system: ExtendedActorSystem): Unit = {
    val behaviorFactory: EntityContext[SagaTransactionCoordinator.Command] => Behavior[SagaTransactionCoordinator.Command] = { context =>
      val i = math.abs(context.entityId.hashCode % SagaTransactionCoordinator.tags.size)
      val selectedTag = SagaTransactionCoordinator.tags(i)
      apply(Id.of(context.entityId), selectedTag, system)
    }

    sharding.init(Entity(SagaTransactionCoordinator.entityTypeKey)(behaviorFactory))
  }

  private def apply(transactionId: Id, tag: String, system: ExtendedActorSystem): Behavior[SagaTransactionCoordinator.Command] = {
    implicit val askTimeout: Timeout = Timeout(30.seconds)

    Behaviors.logMessages(LogOptions().withLogger(LoggerFactory.getLogger("iMadz")).withLevel(Level.INFO),
      Behaviors
        .setup { actorContext =>
          EventSourcedBehavior(
            persistenceId = PersistenceId(entityTypeKey.name, transactionId.toString),
            emptyState = SagaTransactionCoordinator.State.apply(),
            commandHandler = SagaTransactionCoordinator.commandHandler(actorContext, (key, step) =>
              createStepExecutor(actorContext, key, system)),
            eventHandler = SagaTransactionCoordinator.eventHandler
          ).withTagger(_ => Set(tag))
            .eventAdapter(new SagaTransactionCoordinatorEventAdapter(system))
            .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 3))
            .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1).withStashCapacity(100))
        })
  }


  private def createStepExecutor(context: ActorContext[SagaTransactionCoordinator.Command], key: String, extendedActorSystem: ExtendedActorSystem): ActorRef[StepExecutor.Command] = {
    context.spawn(
      StepExecutor[Any, Any](
        PersistenceId.ofUniqueId(key),
        defaultMaxRetries = 5,
        initialRetryDelay = 100.millis,
        circuitBreakerSettings = StepExecutor.CircuitBreakerSettings(5, 30.seconds, 30.seconds),
        extendedSystem = extendedActorSystem
      ), key)
  }
}
