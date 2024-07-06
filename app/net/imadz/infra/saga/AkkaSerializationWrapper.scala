package net.imadz.infra.saga

import akka.actor.ActorSystem
import akka.serialization.{SerializationExtension, Serializers}

case class AkkaSerializationWrapper(system: ActorSystem) {
  private val serialization = SerializationExtension(system)

  def serialize(obj: AnyRef): (Array[Byte], String) = {
    val serializer = serialization.findSerializerFor(obj)
    val bytes = serializer.toBinary(obj)
    val manifest = Serializers.manifestFor(serializer, obj)
    (bytes, manifest)
  }

  def deserialize(bytes: Array[Byte], manifest: String): AnyRef = {
    val deserializer = serialization.serializerFor(Class.forName(manifest))
    deserializer.fromBinary(bytes, Class.forName(manifest)).asInstanceOf[AnyRef]
  }
}