package net.imadz.common.serialization

import akka.actor.{ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import net.imadz.infra.saga.serialization.SagaParticipantSerializerStrategy
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentHashMap

object SerializationExtension extends ExtensionId[SerializationExtensionImpl] with ExtensionIdProvider {
  override def lookup: ExtensionId[SerializationExtensionImpl] = SerializationExtension

  override def createExtension(system: ExtendedActorSystem): SerializationExtensionImpl = new SerializationExtensionImpl(system)

  override def apply(system: ActorSystem): SerializationExtensionImpl = system.extension(this)

  // --- 2. Strategy Registry (remains unchanged) ---
  private val classToStrategy = new ConcurrentHashMap[Class[_], SagaParticipantSerializerStrategy]()
  private val manifestToStrategy = new ConcurrentHashMap[String, SagaParticipantSerializerStrategy]()

  def registerStrategy(strategy: SagaParticipantSerializerStrategy): Unit = {
    println(s"registering: ${strategy.manifest}  -> ${strategy.participantClass} -> ${strategy}")
    classToStrategy.put(strategy.participantClass, strategy)
    manifestToStrategy.put(strategy.manifest, strategy)
  }

  def strategyFor(clazz: Class[_]): SagaParticipantSerializerStrategy = {
    val s = classToStrategy.get(clazz)
    if (s == null) throw new IllegalArgumentException(s"No strategy for class: ${clazz.getName}")
    s
  }

  def strategyFor(manifest: String): SagaParticipantSerializerStrategy = {
    val s = manifestToStrategy.get(manifest)
    if (s == null) throw new IllegalArgumentException(s"No strategy for manifest: $manifest")
    s
  }

  def validateStrategies(): Unit = {
    if (classToStrategy.isEmpty) {
      LoggerFactory.getLogger(getClass).warn("No saga participant serialization strategies registered! This may cause recovery failures.")
    } else {
      LoggerFactory.getLogger(getClass).info(s"SerializationExtension validated with ${classToStrategy.size()} strategies registered.")
    }
  }
}

trait SerializationExtension {
  def registerStrategy(strategy: SagaParticipantSerializerStrategy): Unit

  def strategyFor(clazz: Class[_]): SagaParticipantSerializerStrategy

  def strategyFor(manifest: String): SagaParticipantSerializerStrategy

  def validateStrategies(): Unit
}

class SerializationExtensionImpl(system: ExtendedActorSystem) extends Extension with SerializationExtension {

  def registerStrategy(strategy: SagaParticipantSerializerStrategy): Unit = {
    SerializationExtension.registerStrategy(strategy)
  }

  def strategyFor(clazz: Class[_]): SagaParticipantSerializerStrategy = {
    SerializationExtension.strategyFor(clazz)
  }


  def strategyFor(manifest: String): SagaParticipantSerializerStrategy = {
    SerializationExtension.strategyFor(manifest)
  }

  def validateStrategies(): Unit = {
    SerializationExtension.validateStrategies()
  }
}