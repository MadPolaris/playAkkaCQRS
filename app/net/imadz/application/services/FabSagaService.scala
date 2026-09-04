package net.imadz.application.services

import akka.actor.typed.Scheduler
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.util.Timeout
import net.imadz.application.services.transactor.FabSagaProtocol._
import net.imadz.application.services.transactor.FabSagaTransactionRepository
import net.imadz.common.CommonTypes.{ApplicationService, Id}
import net.imadz.common.Id
import net.imadz.infra.saga.SagaTransactionCoordinator

import javax.inject.Inject
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object FabSagaService {
  /** v3 engine: one shared coordinator pool for every saga definition. */
  val fabSagaCoordinatorKey: EntityTypeKey[SagaTransactionCoordinator.Command] =
    SagaTransactionCoordinator.entityTypeKey
}

class FabSagaService @Inject()(classicSystem: akka.actor.ActorSystem, transactionRepository: FabSagaTransactionRepository)
  extends ApplicationService {

  private val system = classicSystem.toTyped
  private implicit val timeout: Timeout = 120.seconds
  private implicit val ec: ExecutionContext = system.executionContext
  implicit val scheduler: Scheduler = system.scheduler

  def transferWafers(sourceLotId: Id, targetLotId: Id, waferIds: Set[Id], waferNames: Set[String] = Set.empty, existingTransactionId: Option[Id] = None): Future[FabSagaConfirmation] = {
    val transactionId = existingTransactionId.getOrElse(Id.gen)
    val ref = transactionRepository.findTransactionById(transactionId)
    ref.ask(ref => InitiateWaferTransfer(sourceLotId, targetLotId, waferIds, waferNames, ref))
  }

  def splitLot(sourceLotId: Id, targetLotId: Id, waferIds: Set[Id], waferNames: Set[String] = Set.empty, existingTransactionId: Option[Id] = None): Future[FabSagaConfirmation] = {
    val transactionId = existingTransactionId.getOrElse(Id.gen)
    val ref = transactionRepository.findTransactionById(transactionId)
    ref.ask(ref => InitiateLotSplit(sourceLotId, targetLotId, waferIds, waferNames, ref))
  }

  def mergeLots(sourceLotIds: List[Id], targetLotId: Id, waferIds: Set[Id], waferNames: Set[String] = Set.empty, existingTransactionId: Option[Id] = None): Future[FabSagaConfirmation] = {
    val transactionId = existingTransactionId.getOrElse(Id.gen)
    val ref = transactionRepository.findTransactionById(transactionId)
    ref.ask(ref => InitiateLotMerge(sourceLotIds, targetLotId, waferIds, waferNames, ref))
  }
}
