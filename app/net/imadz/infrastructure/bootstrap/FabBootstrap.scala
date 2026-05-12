package net.imadz.infrastructure.bootstrap

import akka.actor.ExtendedActorSystem
import akka.actor.typed.{Behavior, LogOptions, SupervisorStrategy}
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityContext, EntityTypeKey}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{EventSourcedBehavior, RetentionCriteria}
import net.imadz.application.aggregates.LotAggregate.LotEntityTypeKey
import net.imadz.application.aggregates.WaferAggregate.WaferEntityTypeKey
import net.imadz.application.aggregates.LotProtocol.LotCommand
import net.imadz.application.aggregates.WaferProtocol.WaferCommand
import net.imadz.application.aggregates.{LotAggregate, WaferAggregate}
import net.imadz.application.aggregates.process.{FabProcessAggregate, FabProcessProtocol}
import net.imadz.application.aggregates.process.FabProcessProtocol.FabProcessCommand
import net.imadz.application.aggregates.repository.{LotRepository, WaferRepository}
import net.imadz.application.services.FabSagaService
import net.imadz.application.services.transactor.{FabSagaProtocol, FabSagaTransactor, FabTransactionContext}
import net.imadz.common.CommonTypes.Id
import net.imadz.common.Id
import net.imadz.common.serialization.SerializationExtension
import net.imadz.domain.entities.{FabProcessEntity, LotEntity, WaferEntity}
import net.imadz.domain.entities.behaviors.{FabProcessEventHandler, LotEventHandler, WaferEventHandler}
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
        ).withTagger(_ => Set(tag))
          .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 3))
          .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1).withStashCapacity(100))
          .eventAdapter(new LotEventAdapter)
          .snapshotAdapter(new LotSnapshotAdapter)
      })

  // --- Wafer Aggregate ---
  def initWaferAggregate(sharding: ClusterSharding): Unit = {
    val behaviorFactory: EntityContext[WaferCommand] => Behavior[WaferCommand] = { context =>
      val i = math.abs(context.entityId.hashCode % WaferAggregate.tags.size)
      val selectedTag = WaferAggregate.tags(i)
      applyWafer(Id.of(context.entityId), selectedTag)
    }
    sharding.init(Entity(WaferAggregate.WaferEntityTypeKey)(behaviorFactory))
  }

  private def applyWafer(waferId: Id, tag: String): Behavior[WaferCommand] =
    Behaviors.logMessages(LogOptions().withLogger(LoggerFactory.getLogger("iMadz")).withLevel(Level.INFO),
      Behaviors.setup { actorContext =>
        EventSourcedBehavior(
          persistenceId = PersistenceId(WaferEntityTypeKey.name, waferId.toString),
          emptyState = WaferEntity.empty(waferId),
          commandHandler = WaferAggregate.commandHandler(actorContext),
          eventHandler = WaferEventHandler.apply
        ).withTagger(_ => Set(tag))
          .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 3))
          .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1).withStashCapacity(100))
          .eventAdapter(new WaferEventAdapter)
          .snapshotAdapter(new WaferSnapshotAdapter)
      })

  // --- Fab Process Aggregate ---
  def initFabProcessAggregate(sharding: ClusterSharding): Unit = {
    val behaviorFactory: EntityContext[FabProcessCommand] => Behavior[FabProcessCommand] = { context =>
      val i = math.abs(context.entityId.hashCode % FabProcessAggregate.tags.size)
      val selectedTag = FabProcessAggregate.tags(i)
      applyProcess(context.entityId, selectedTag)
    }
    sharding.init(Entity(FabProcessAggregate.ProcessEntityTypeKey)(behaviorFactory))
  }

  private def applyProcess(processId: String, tag: String): Behavior[FabProcessCommand] =
    Behaviors.logMessages(LogOptions().withLogger(LoggerFactory.getLogger("iMadz")).withLevel(Level.INFO),
      Behaviors.setup { actorContext =>
        EventSourcedBehavior(
          persistenceId = PersistenceId(FabProcessAggregate.ProcessEntityTypeKey.name, processId),
          emptyState = FabProcessEntity.empty(processId),
          commandHandler = FabProcessAggregate.commandHandler(actorContext),
          eventHandler = FabProcessEventHandler.apply
        ).withTagger(_ => Set(tag))
          .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 50, keepNSnapshots = 3))
          .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1).withStashCapacity(100))
          .eventAdapter(new ProcessEventAdapter)
      })

  // --- Fab Saga Transactor ---
  def initFabSagaTransactor(
    coordinatorEntityKey: EntityTypeKey[SagaTransactionCoordinator.Command],
    sharding: ClusterSharding,
    lotRepository: LotRepository,
    waferRepository: WaferRepository
  ): Unit = {
    val behaviorFactory: EntityContext[FabSagaProtocol.FabSagaCommand] => Behavior[FabSagaProtocol.FabSagaCommand] = { context =>
      val transactionId = context.entityId
      val coordinator = sharding.entityRefFor(coordinatorEntityKey, transactionId)
      FabSagaTransactor.apply(transactionId, coordinator, FabTransactionContext(lotRepository, waferRepository))
    }
    sharding.init(Entity(FabSagaTransactor.entityTypeKey)(behaviorFactory))
  }

  // --- Fab Saga Coordinator ---
  def initFabSagaCoordinator(
    sharding: ClusterSharding,
    lotRepository: LotRepository,
    waferRepository: WaferRepository,
    system: ExtendedActorSystem,
    sagaTransactionCoordinatorBootstrap: SagaTransactionCoordinatorBootstrap
  ): Unit = {
    sagaTransactionCoordinatorBootstrap.initSagaTransactionCoordinatorAggregate[FabTransactionContext](
      sharding = sharding,
      context = FabTransactionContext(lotRepository, waferRepository),
      entityTypeKey = FabSagaService.fabSagaCoordinatorKey,
      system = system
    )
  }

  // --- Serialization ---
  def registerFabSerializationStrategies(
    serializationExtension: SerializationExtension,
    lotRepository: LotRepository,
    waferRepository: WaferRepository
  )(implicit ec: ExecutionContext): Unit = {
    serializationExtension.registerStrategy(FabSerializationStrategies.SourceLotStrategy(lotRepository))
    serializationExtension.registerStrategy(FabSerializationStrategies.TargetLotStrategy(lotRepository))
    serializationExtension.registerStrategy(FabSerializationStrategies.WaferTransferStrategy(waferRepository))
  }
}
