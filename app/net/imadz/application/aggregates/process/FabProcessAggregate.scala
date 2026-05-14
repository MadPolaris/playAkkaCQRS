package net.imadz.application.aggregates.process

import akka.actor.typed.scaladsl.ActorContext
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import net.imadz.application.aggregates.process.FabProcessProtocol.{FabProcessCommand, FabProcessCommandHandler}

object FabProcessAggregate {

  def commandHandler(context: ActorContext[FabProcessCommand]): FabProcessCommandHandler =
    FabProcessBehaviors.apply(context)

  val ProcessEntityTypeKey: EntityTypeKey[FabProcessCommand] = EntityTypeKey("FabProcess")
  val tags: Vector[String] = Vector.tabulate(2)(i => s"process-$i")
}
