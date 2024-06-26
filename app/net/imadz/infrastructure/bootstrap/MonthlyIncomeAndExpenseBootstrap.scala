package net.imadz.infrastructure.bootstrap

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, ShardedDaemonProcess}
import akka.projection.ProjectionBehavior
import net.imadz.application.aggregates.CreditBalanceAggregate
import net.imadz.application.projection.MonthlyIncomeAndExpenseSummaryProjection.{createProjection, projectionName}
import net.imadz.application.projection.repository.MonthlyIncomeAndExpenseSummaryRepository

trait MonthlyIncomeAndExpenseBootstrap {
  def initMonthlySummaryProjection(system: ActorSystem[_], sharding: ClusterSharding, repository: MonthlyIncomeAndExpenseSummaryRepository): Unit = {
    ShardedDaemonProcess(system).init(
      name = projectionName,
      numberOfInstances = CreditBalanceAggregate.tags.size,
      behaviorFactory = index => ProjectionBehavior(createProjection(system, sharding, index, repository)),
      settings = ShardedDaemonProcessSettings(system),
      stopMessage = Some(ProjectionBehavior.Stop)
    )
  }
}
