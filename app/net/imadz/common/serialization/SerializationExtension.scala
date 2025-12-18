package net.imadz.common.serialization

import akka.actor.{ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import net.imadz.infra.saga.serialization.SagaParticipantSerializerStrategy

import java.util.concurrent.ConcurrentHashMap
import scala.reflect.ClassTag

object SerializationExtension extends ExtensionId[SerializationExtensionImpl] with ExtensionIdProvider {
  override def lookup: ExtensionId[SerializationExtensionImpl] = SerializationExtension
  override def createExtension(system: ExtendedActorSystem): SerializationExtensionImpl = new SerializationExtensionImpl(system)
  override def apply(system: ActorSystem): SerializationExtensionImpl = system.extension(this)
}

class SerializationExtensionImpl(system: ExtendedActorSystem) extends Extension {

  // --- 1. 通用依赖容器 (The Container) ---
  // Key: 依赖的接口类型 (如 classOf[CreditBalanceRepository])
  // Value: 具体的实例
  private val dependencies = new ConcurrentHashMap[Class[_], Any]()

  def registerDependency[T](instance: T)(implicit tag: ClassTag[T]): Unit = {
    dependencies.put(tag.runtimeClass, instance)
    system.log.info(s"Registered dependency: ${tag.runtimeClass.getName}")
  }

  def getDependency[T](implicit tag: ClassTag[T]): T = {
    val clazz = tag.runtimeClass
    val instance = dependencies.get(clazz)
    if (instance == null) {
      throw new IllegalStateException(s"Dependency not found: ${clazz.getName}. Please register it in Bootstrap.")
    }
    instance.asInstanceOf[T]
  }

  // --- 2. 策略注册表 (保持不变) ---
  private val classToStrategy = new ConcurrentHashMap[Class[_], SagaParticipantSerializerStrategy]()
  private val manifestToStrategy = new ConcurrentHashMap[String, SagaParticipantSerializerStrategy]()

  def registerStrategy(strategy: SagaParticipantSerializerStrategy): Unit = {
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
}