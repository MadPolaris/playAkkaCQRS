package net.imadz.application.aggregates

import akka.actor.typed.scaladsl.ActorContext
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import net.imadz.application.aggregates.LotProtocol.{LotCommand, LotCommandHandler}
import net.imadz.application.aggregates.behaviors.LotBehaviors

object LotAggregate {

  def commandHandler(context: ActorContext[LotCommand]): LotCommandHandler = LotBehaviors.apply(context)

  val LotEntityTypeKey: EntityTypeKey[LotCommand] = EntityTypeKey("Lot")
  val tags: Vector[String] = Vector.tabulate(5)(i => s"lot-$i")
}
