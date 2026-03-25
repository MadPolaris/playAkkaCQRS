package net.imadz.application.aggregates

import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import net.imadz.application.aggregates.CreditBalanceProtocol.{CreditBalanceCommand, CreditBalanceCommandHandler}
import net.imadz.application.aggregates.behaviors.CreditBalanceBehaviors

object CreditBalanceAggregate {

  // Command Handler
  def commandHandler: CreditBalanceCommandHandler = CreditBalanceBehaviors.apply

  // Akka Sharding Configuration
  val CreditBalanceEntityTypeKey: EntityTypeKey[CreditBalanceCommand] = EntityTypeKey("CreditBalance")
  val tags: Vector[String] = Vector.tabulate(5)(i => s"credit-balance-$i")
}
