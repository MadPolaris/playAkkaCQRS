package net.imadz.application.aggregates

import akka.actor.typed.scaladsl.ActorContext
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import net.imadz.application.aggregates.behaviors.FabProcessBehaviors
import FabProcessProtocol.{FabProcessCommand, FabProcessCommandHandler}

object FabProcessAggregate {

  def commandHandler(context: ActorContext[FabProcessCommand]): FabProcessCommandHandler =
    FabProcessBehaviors.apply(context)

  val ProcessEntityTypeKey: EntityTypeKey[FabProcessCommand] = EntityTypeKey("FabProcess")
  val tags: Vector[String] = Vector.tabulate(1)(i => s"process-$i")
}
