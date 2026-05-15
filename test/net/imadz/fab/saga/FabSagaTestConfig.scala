package net.imadz.fab.saga

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, Behavior}
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import com.typesafe.config.ConfigFactory
import net.imadz.application.aggregates.LotProtocol.LotCommand
import net.imadz.application.aggregates.LotAggregate
import net.imadz.common.CommonTypes.Id
import net.imadz.domain.entities.LotEntity
import net.imadz.domain.entities.LotEntity.{LotEvent, LotState}
import net.imadz.domain.entities.behaviors.LotEventHandler

import java.util.UUID

/**
 * Shared test configuration and factory methods for Fab aggregate tests.
 */
object FabSagaTestConfig {

  val genId: Id = UUID.randomUUID()

  val testConfig = ConfigFactory.parseString(
    """
      |akka {
      |  extensions = ["net.imadz.common.serialization.SerializationExtension"]
      |  actor {
      |    allow-java-serialization = on
      |    warn-about-java-serializer-usage = off
      |    serialization-bindings {
      |      "java.io.Serializable" = java
      |    }
      |  }
      |}
      |akka.test.single-expect-default = 10s
      |akka.actor.testkit.typed.single-expect-default = 10s
      |akka.actor.testkit.typed.serialize-messages = off
      |akka.actor.testkit.typed.serialize-creators = off
      |akka.actor.testkit.typed.serialization.verify = off
      |akka.persistence.testkit.events.serialize = off
      |""".stripMargin
  ).withFallback(EventSourcedBehaviorTestKit.config)

  /** Create an EventSourcedBehaviorTestKit for a Lot aggregate */
  def createLotTestKit(lotId: Id)(implicit system: ActorSystem[_]): EventSourcedBehaviorTestKit[LotCommand, LotEvent, LotState] = {
    val behavior: Behavior[LotCommand] = Behaviors.setup[LotCommand] { ctx =>
      EventSourcedBehavior(
        persistenceId = PersistenceId("Lot", lotId.toString),
        emptyState = LotEntity.empty(lotId),
        commandHandler = LotAggregate.commandHandler(ctx),
        eventHandler = LotEventHandler.apply
      )
    }
    EventSourcedBehaviorTestKit[LotCommand, LotEvent, LotState](system, behavior)
  }

  def lotId(name: String): Id = UUID.nameUUIDFromBytes(s"test-lot-$name".getBytes)
}
