package net.imadz.infrastructure.connector

/** 编码/解码策略——将领域对象与传输格式双向转换 */
trait EncodingStrategy[Raw, Encoded] {
  def encode(raw: Raw): Encoded
  def decode(encoded: Encoded): Raw
  def validate(encoded: Encoded): Either[String, Raw]
}

object EncodingStrategy {

  /** JSON 编码（需要 implicit Reads/Writes 或 circe 编解码器） */
  trait JsonEncoding[Raw] extends EncodingStrategy[Raw, String] {
    def toJson(raw: Raw): String
    def fromJson(json: String): Either[String, Raw]

    override def encode(raw: Raw): String = toJson(raw)
    override def decode(encoded: String): Raw =
      fromJson(encoded).fold(err => throw new IllegalArgumentException(s"JSON decode: $err"), identity)
    override def validate(encoded: String): Either[String, Raw] = fromJson(encoded)
  }

  /** XML 编码 */
  trait XmlEncoding[Raw] extends EncodingStrategy[Raw, String] {
    def toXml(raw: Raw): String
    def fromXml(xml: String): Either[String, Raw]

    override def encode(raw: Raw): String = toXml(raw)
    override def decode(encoded: String): Raw =
      fromXml(encoded).fold(err => throw new IllegalArgumentException(s"XML parse: $err"), identity)
    override def validate(encoded: String): Either[String, Raw] = fromXml(encoded)
  }

  /** CSV 编码 */
  trait CsvEncoding[Raw] extends EncodingStrategy[Raw, String] {
    def toCsv(rows: Seq[Raw]): String
    def fromCsv(csv: String): Either[String, Seq[Raw]]

    override def encode(raw: Raw): String = toCsv(Seq(raw))
    override def decode(encoded: String): Raw =
      fromCsv(encoded).fold(err => throw new IllegalArgumentException(s"CSV parse: $err"), _.head)
    override def validate(encoded: String): Either[String, Raw] =
      fromCsv(encoded).flatMap(rows => rows.headOption.toRight("empty CSV"))
  }

  /** 加密文件编码——先序列化再加密 */
  trait EncryptedFileEncoding[Raw] extends EncodingStrategy[Raw, Array[Byte]] {
    def serialize(raw: Raw): Array[Byte]
    def deserialize(bytes: Array[Byte]): Either[String, Raw]
    def encrypt(plain: Array[Byte]): Array[Byte]
    def decrypt(cipher: Array[Byte]): Array[Byte]

    override def encode(raw: Raw): Array[Byte] = encrypt(serialize(raw))
    override def decode(encoded: Array[Byte]): Raw =
      deserialize(decrypt(encoded)).fold(err => throw new IllegalArgumentException(s"Decrypt: $err"), identity)
    override def validate(encoded: Array[Byte]): Either[String, Raw] =
      deserialize(decrypt(encoded))
  }

  /** 透传编码——原始字节不做转换 */
  class PassthroughEncoding extends EncodingStrategy[Array[Byte], Array[Byte]] {
    override def encode(raw: Array[Byte]): Array[Byte] = raw
    override def decode(encoded: Array[Byte]): Array[Byte] = encoded
    override def validate(encoded: Array[Byte]): Either[String, Array[Byte]] = Right(encoded)
  }
}
