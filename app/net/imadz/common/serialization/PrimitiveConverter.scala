package net.imadz.common.serialization

import net.imadz.common.CommonTypes.Id

import java.util.{Currency, UUID}

trait PrimitiveConverter {

  trait ProtoConverter[S, P] {
    def toProto(domain: S): P
    def fromProto(proto: P): S

    // [既有] Option 支持
    def toProtoOption(domain: Option[S]): Option[P] = domain.map(toProto)
    def fromProtoOption(proto: Option[P]): Option[S] = proto.map(fromProto)

    // [既有] List 支持
    def toProtoList(domain: Seq[S]): Seq[P] = domain.map(toProto)
    def fromProtoList(proto: Seq[P]): List[S] = proto.map(fromProto).toList
  }

  // --- [新增] Map 通用转换逻辑 ---
  // 这就是那艘“法船”，一次性解决 Key 和 Value 的双重转换
  def toProtoMap[K1, V1, K2, V2](
                                  domain: Map[K1, V1],
                                  keyConv: ProtoConverter[K1, K2],
                                  valConv: ProtoConverter[V1, V2]
                                ): Map[K2, V2] = {
    domain.map { case (k, v) =>
      keyConv.toProto(k) -> valConv.toProto(v)
    }
  }

  def fromProtoMap[K1, V1, K2, V2](
                                    proto: Map[K1, V1],
                                    keyConv: ProtoConverter[K2, K1],
                                    valConv: ProtoConverter[V2, V1]
                                  ): Map[K2, V2] = {
    proto.map { case (k, v) =>
      keyConv.fromProto(k) -> valConv.fromProto(v)
    }
  }

  // --- 基础转换器 ---

  object IdConv extends ProtoConverter[Id, String] {
    override def toProto(id: Id): String = id.toString
    override def fromProto(s: String): Id = UUID.fromString(s)
  }

  object CurrencyConv extends ProtoConverter[Currency, String] {
    override def toProto(c: Currency): String = c.getCurrencyCode
    override def fromProto(s: String): Currency = Currency.getInstance(s)
  }

  // --- [新增] 字符串直通转换器 ---
  // 用于处理 Map[String, Money] 这种 Key 不需要转换的情况，保持接口一致性
  object StringConv extends ProtoConverter[String, String] {
    override def toProto(s: String): String = s
    override def fromProto(s: String): String = s
  }
}