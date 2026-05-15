package net.imadz.infrastructure.bootstrap

import akka.actor.ExtendedActorSystem
import akka.actor.typed.{ActorSystem, Behavior, LogOptions, SupervisorStrategy}
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityContext, EntityTypeKey, ShardedDaemonProcess}
import akka.projection.ProjectionBehavior
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{EventSourcedBehavior, RetentionCriteria}
import net.imadz.application.aggregates.LotAggregate.LotEntityTypeKey
import net.imadz.application.aggregates.LotProtocol.LotCommand
import net.imadz.application.aggregates.{LotAggregate, WorkOrderAggregate, WorkOrderProtocol}
import WorkOrderProtocol.WorkOrderCommand
import net.imadz.application.projection.{FabLotProjection, FabSagaTransactionProjection, WorkOrderProjection}
import net.imadz.application.aggregates.repository.LotRepository
import net.imadz.application.services.FabSagaService
import net.imadz.application.services.transactor.{FabSagaProtocol, FabSagaTransactor, FabTransactionContext}
import net.imadz.common.CommonTypes.Id
import net.imadz.common.Id
import net.imadz.common.serialization.SerializationExtension
import net.imadz.domain.entities.LotEntity
import net.imadz.domain.entities.behaviors.LotEventHandler
import net.imadz.infra.saga.SagaTransactionCoordinator
import net.imadz.infrastructure.persistence._
import net.imadz.infrastructure.persistence.strategies.FabSerializationStrategies
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

trait FabBootstrap {

  // --- Lot Aggregate ---
  def initLotAggregate(sharding: ClusterSharding): Unit = {
    val behaviorFactory: EntityContext[LotCommand] => Behavior[LotCommand] = { context =>
      val i = math.abs(context.entityId.hashCode % LotAggregate.tags.size)
      val selectedTag = LotAggregate.tags(i)
      applyLot(Id.of(context.entityId), selectedTag)
    }
    sharding.init(Entity(LotAggregate.LotEntityTypeKey)(behaviorFactory))
  }

  private def applyLot(lotId: Id, tag: String): Behavior[LotCommand] =
    Behaviors.logMessages(LogOptions().withLogger(LoggerFactory.getLogger("iMadz")).withLevel(Level.INFO),
      Behaviors.setup { actorContext =>
        EventSourcedBehavior(
          persistenceId = PersistenceId(LotEntityTypeKey.name, lotId.toString),
          emptyState = LotEntity.empty(lotId),
          commandHandler = LotAggregate.commandHandler(actorContext),
          eventHandler = LotEventHandler.apply
        ).withTagger(_ => Set(tag, "fab-view"))
          .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 3))
          .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1).withStashCapacity(100))
          .eventAdapter(new LotEventAdapter)
          .snapshotAdapter(new LotSnapshotAdapter)
      })

  // --- Work Order Aggregate ---
  def initWorkOrderAggregate(sharding: ClusterSharding): Unit = {
    // WorkOrder.init is called from FabDemoService, which provides the pipelineStarter
    // This method is reserved for future use when WorkOrder becomes self-contained
  }

  // --- Fab Saga Transactor ---
  def initFabSagaTransactor(
    coordinatorEntityKey: EntityTypeKey[SagaTransactionCoordinator.Command],
    sharding: ClusterSharding,
    lotRepository: LotRepository
  ): Unit = {
    val behaviorFactory: EntityContext[FabSagaProtocol.FabSagaCommand] => Behavior[FabSagaProtocol.FabSagaCommand] = { context =>
      val transactionId = context.entityId
      val coordinator = sharding.entityRefFor(coordinatorEntityKey, transactionId)
      FabSagaTransactor.apply(transactionId, coordinator, FabTransactionContext(lotRepository))
    }
    sharding.init(Entity(FabSagaTransactor.entityTypeKey)(behaviorFactory))
  }

  // --- Fab Saga Coordinator ---
  def initFabSagaCoordinator(
    sharding: ClusterSharding,
    lotRepository: LotRepository,
    system: ExtendedActorSystem,
    sagaTransactionCoordinatorBootstrap: SagaTransactionCoordinatorBootstrap
  ): Unit = {
    sagaTransactionCoordinatorBootstrap.initSagaTransactionCoordinatorAggregate[FabTransactionContext](
      sharding = sharding,
      context = FabTransactionContext(lotRepository),
      entityTypeKey = FabSagaService.fabSagaCoordinatorKey,
      system = system
    )
  }

  // --- Work Order Projection ---
  def initWorkOrderProjection(system: ActorSystem[_]): Unit = {
    ShardedDaemonProcess(system).init(
      name = WorkOrderProjection.projectionName,
      numberOfInstances = WorkOrderAggregate.tags.size,
      behaviorFactory = index => ProjectionBehavior(WorkOrderProjection.createProjection(system, index)),
      settings = ShardedDaemonProcessSettings(system),
      stopMessage = Some(ProjectionBehavior.Stop)
    )
  }

  // --- Fab Lot Projection ---
  def initFabLotProjection(system: ActorSystem[_]): Unit = {
    ShardedDaemonProcess(system).init(
      name = FabLotProjection.projectionName,
      numberOfInstances = LotAggregate.tags.size,
      behaviorFactory = index => ProjectionBehavior(FabLotProjection.createProjection(system, index)),
      settings = ShardedDaemonProcessSettings(system),
      stopMessage = Some(ProjectionBehavior.Stop)
    )
  }

  // --- Fab Saga Transaction Projection ---
  def initFabSagaTransactionProjection(system: ActorSystem[_]): Unit = {
    ShardedDaemonProcess(system).init(
      name = FabSagaTransactionProjection.projectionName,
      numberOfInstances = FabSagaTransactionProjection.tags.size,
      behaviorFactory = index => ProjectionBehavior(FabSagaTransactionProjection.createProjection(system, index)),
      settings = ShardedDaemonProcessSettings(system),
      stopMessage = Some(ProjectionBehavior.Stop)
    )
  }

  // --- Serialization ---
  def registerFabSerializationStrategies(
    serializationExtension: SerializationExtension,
    lotRepository: LotRepository
  )(implicit ec: ExecutionContext): Unit = {
    serializationExtension.registerStrategy(FabSerializationStrategies.SourceLotStrategy(lotRepository))
    serializationExtension.registerStrategy(FabSerializationStrategies.TargetLotStrategy(lotRepository))
  }
}
