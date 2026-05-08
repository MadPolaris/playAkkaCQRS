package net.imadz.m25.pipeline

import net.imadz.infrastructure.connector.EncodingStrategy
import net.imadz.m25.component.{ResponseFile, ResponseParser}

import scala.concurrent.{ExecutionContext, Future}

/**
 * 响应解析阶段——将外部系统返回的原始响应解码为结构化结果。
 *
 * 支持多种编码策略：XML、JSON、CSV、定长文本、加密文件等。
 */
class EncodedResponseParser[RawResult](
    encoding: EncodingStrategy[RawResult, String],
    /** 将单个解码结果展平为多条记录（如 XML 中的 <item> 列表） */
    splitter: RawResult => Seq[RawResult] = (r: RawResult) => Seq(r)
)(implicit ec: ExecutionContext) extends ResponseParser[RawResult] {

  override def parse(file: ResponseFile, context: Map[String, Any]): Future[Seq[RawResult]] = Future {
    val rawString = new String(file.content, "UTF-8")
    val decoded = encoding.decode(rawString)
    splitter(decoded)
  }
}

/**
 * 验证并解析——先验证格式再解码。
 * 解析失败时返回空列表（由上游 ResultClassifier 处理缺失项）。
 */
class ValidatingResponseParser[RawResult](
    encoding: EncodingStrategy[RawResult, String],
    splitter: RawResult => Seq[RawResult] = (r: RawResult) => Seq(r)
)(implicit ec: ExecutionContext) extends ResponseParser[RawResult] {

  override def parse(file: ResponseFile, context: Map[String, Any]): Future[Seq[RawResult]] = Future {
    val rawString = new String(file.content, "UTF-8")
    encoding.validate(rawString) match {
      case Right(decoded) => splitter(decoded)
      case Left(err) =>
        throw new IllegalArgumentException(s"Response validation failed for ${file.fileName}: $err")
    }
  }
}

/**
 * 银行回盘文件的 XML 响应解析器示例。
 *
 * 典型 XML 结构：
 *   <response>
 *     <items>
 *       <item code="OK" ref="充值1001" amount="100.00"/>
 *       <item code="TIMEOUT" ref="充值1002" amount="200.00"/>
 *     </items>
 *   </response>
 *
 * 使用时注入具体的 XML 解析实现（scala-xml / Jackson / etc）。
 */
abstract class XmlResponseParser[Item](
)(implicit ec: ExecutionContext) extends ResponseParser[Item] {

  /** 解析 XML 字符串为 Item 列表 */
  def parseXml(xml: String): Seq[Item]

  override def parse(file: ResponseFile, context: Map[String, Any]): Future[Seq[Item]] = Future {
    val xmlString = new String(file.content, "UTF-8")
    parseXml(xmlString)
  }
}
