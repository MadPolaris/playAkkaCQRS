package net.imadz.application.aggregates

import akka.actor.typed.scaladsl.ActorContext
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import net.imadz.application.aggregates.WaferProtocol.{WaferCommand, WaferCommandHandler}
import net.imadz.application.aggregates.behaviors.WaferBehaviors

object WaferAggregate {

  def commandHandler(context: ActorContext[WaferCommand]): WaferCommandHandler = WaferBehaviors.apply(context)

  val WaferEntityTypeKey: EntityTypeKey[WaferCommand] = EntityTypeKey("Wafer")
  val tags: Vector[String] = Vector.tabulate(2)(i => s"wafer-$i")
}
