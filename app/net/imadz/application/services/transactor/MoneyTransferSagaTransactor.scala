package net.imadz.application.services.transactor

import akka.actor.typed.Behavior
import akka.cluster.sharding.typed.scaladsl.{EntityRef, EntityTypeKey}
import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.application.services.transactor.MoneyTransferProtocol.MoneyTransferTransactionCommand
import net.imadz.application.services.transactor.behaviors.MoneyTransferSagaTransactorBehaviors
import net.imadz.infra.saga.SagaTransactionCoordinator

object MoneyTransferSagaTransactor {

  // --- Sharding Key ---
  // 引用 Protocol 中的 Command 类型
  val entityTypeKey: EntityTypeKey[MoneyTransferTransactionCommand] = EntityTypeKey("MoneyTransferTransaction")

  // --- Factory ---
  def apply(
             id: String,
             coordinator: EntityRef[SagaTransactionCoordinator.Command],
             repository: CreditBalanceRepository
           ): Behavior[MoneyTransferTransactionCommand] = {
    // 委托给分离的行为对象
    MoneyTransferSagaTransactorBehaviors.apply(id, coordinator, repository)
  }
}