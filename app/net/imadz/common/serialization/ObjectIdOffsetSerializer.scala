package net.imadz.common.serialization

import akka.contrib.persistence.mongodb.ObjectIdOffset
import akka.serialization.Serializer
import reactivemongo.api.bson.BSONObjectID

import java.math.BigInteger

class ObjectIdOffsetSerializer extends Serializer {
  override def identifier: Int = 20160728

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case ObjectIdOffset(hexString, _) =>
      new BigInteger(hexString, 16).toByteArray
    case _ => throw new IllegalArgumentException("Only for ObjectIdOffset")
  }

  override def includeManifest: Boolean = true

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    BSONObjectID.parse((new BigInteger(bytes)).toString(16))
      .map(id => ObjectIdOffset(id.stringify, id.time))
      .getOrElse(throw new IllegalStateException("Only for ObjectIdOffset"))
  }
}
