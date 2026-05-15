package net.imadz.m25.component

import scala.concurrent.Future

/**
 * 小批次处理流水线——每个阶段是独立接口，可自由组合替换。
 *
 * 流水线阶段：
 *   items → fileGen → upload → waitAck → pollResp → parse → classify → results
 */
case class SubBatchPipeline[Item, RawResult](
    fileGen:   FileGenerator[Item],
    upload:    FileUploader,
    waitAck:   AckWaiter,
    pollResp:  ResponsePoller,
    parse:     ResponseParser[RawResult],
    classify:  ResultClassifier[RawResult, Item]
)

// ---- Pipeline Stage Interfaces ----

/** 文件生成——将业务 items 编码为传输文件 */
trait FileGenerator[Item] {
  def generate(items: Seq[Item], context: Map[String, Any]): Future[GeneratedFile]
}

case class GeneratedFile(
    localPath: String,
    fileName: String,
    byteSize: Long,
    encoding: String
)

/** 文件上传——将本地文件发送到外部系统 */
trait FileUploader {
  def upload(file: GeneratedFile, context: Map[String, Any]): Future[UploadReceipt]
}

case class UploadReceipt(
    remotePath: String,
    bytesTransferred: Long,
    timestamp: Long
)

/** 外部系统确认——等待外部系统确认收到 */
trait AckWaiter {
  def waitForAck(receipt: UploadReceipt, context: Map[String, Any]): Future[AckResult]
}

sealed trait AckResult
case object AckReceived extends AckResult
final case class AckTimeout(waitMs: Long) extends AckResult
final case class AckRejected(reason: String) extends AckResult

/** 响应轮询——从外部系统拉取处理结果 */
trait ResponsePoller {
  def poll(context: Map[String, Any]): Future[PollResult]
}

sealed trait PollResult
final case class ResponseReady(file: ResponseFile) extends PollResult
final case class PollTimeout(attempts: Int, waitMs: Long) extends PollResult
final case class PollError(cause: Throwable) extends PollResult

case class ResponseFile(
    localPath: String,
    fileName: String,
    byteSize: Long,
    content: Array[Byte]
)

/** 响应解析——将原始响应解码为结构化结果 */
trait ResponseParser[RawResult] {
  def parse(file: ResponseFile, context: Map[String, Any]): Future[Seq[RawResult]]
}
