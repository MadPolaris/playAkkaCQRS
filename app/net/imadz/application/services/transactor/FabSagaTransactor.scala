package net.imadz.application.services.transactor

import akka.actor.typed.Behavior
import akka.cluster.sharding.typed.scaladsl.{EntityRef, EntityTypeKey}
import net.imadz.application.services.transactor.FabSagaProtocol.FabSagaCommand
import net.imadz.application.services.transactor.behaviors.FabSagaTransactorBehaviors
import net.imadz.infra.saga.SagaTransactionCoordinator

object FabSagaTransactor {

  val entityTypeKey: EntityTypeKey[FabSagaCommand] = EntityTypeKey("FabSagaTransaction")
  val tags: Vector[String] = Vector.tabulate(5)(i => s"fabsaga-$i")

  def apply(
    id: String,
    coordinator: EntityRef[SagaTransactionCoordinator.Command],
    fabContext: FabTransactionContext
  ): Behavior[FabSagaCommand] = {
    FabSagaTransactorBehaviors.apply(id, coordinator, fabContext)
  }
}
