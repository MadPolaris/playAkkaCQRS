package net.imadz.m25.component

import scala.concurrent.{ExecutionContext, Future}

/**
 * 小批次处理器——核心组件。
 *
 * 处理一个小批次与外部系统的完整交互周期：
 *   items → fileGen → upload → waitAck → pollResp → parse → classify → results
 *
 * SubBatchProcessor 只知道 Pipeline 的接口，不关心具体实现。
 * 同一个 Processor 可以处理充值、申购、Fab 光刻等不同场景——
 * 区别仅在于注入的 SubBatchPipeline 参数不同。
 */
class SubBatchProcessor[Item, RawResult](
    pipeline: SubBatchPipeline[Item, RawResult],
    /** 阶段完成回调——每个 Pipeline 阶段完成后触发，由 ChainExecutionActor 用于持久化 PhaseCompleted 事件。 */
    onPhaseComplete: (String, Map[String, String]) => Unit = (_, _) => ()
)(implicit ec: ExecutionContext) {

  /**
   * 处理一个小批次，返回三分类结果。
   */
  def process(batch: SubBatch[Item]): Future[SubBatchResult[Classification[Item]]] = {
    val ctx = batch.context

    for {
      // 1. 文件生成
      generatedFile <- pipeline.fileGen.generate(batch.items, ctx)
      _ = onPhaseComplete("file-gen", Map(
        "localPath" -> generatedFile.localPath,
        "fileName"  -> generatedFile.fileName,
        "byteSize"  -> generatedFile.byteSize.toString,
        "encoding"  -> generatedFile.encoding
      ))

      // 2. 文件上传
      receipt <- pipeline.upload.upload(generatedFile, ctx)
      _ = onPhaseComplete("upload", Map(
        "remotePath"       -> receipt.remotePath,
        "bytesTransferred" -> receipt.bytesTransferred.toString,
        "timestamp"        -> receipt.timestamp.toString
      ))

      // 3. 等待外部系统确认
      ack <- pipeline.waitAck.waitForAck(receipt, ctx)
      _    <- handleAck(ack, batch.batchId)
      _ = onPhaseComplete("wait-ack", Map(
        "ackResult" -> ack.getClass.getSimpleName
      ))

      // 4. 轮询响应
      pollResult <- pipeline.pollResp.poll(ctx)
      responseFile <- handlePoll(pollResult, batch.batchId)
      _ = onPhaseComplete("poll", Map(
        "localPath" -> responseFile.localPath,
        "fileName"  -> responseFile.fileName,
        "byteSize"  -> responseFile.byteSize.toString
      ))

      // 5. 解析响应
      rawResults <- pipeline.parse.parse(responseFile, ctx)
      _ = onPhaseComplete("parse", Map(
        "resultCount" -> rawResults.size.toString
      ))

      // 6. 分类
      classifications <- pipeline.classify.classify(rawResults, batch.items)

    } yield {
      val successes = classifications.collect { case s: Success[Item] => s }
      val failures  = classifications.collect { case f: Failure[Item] => f }
      val suspicious = classifications.collect { case s: Suspicious[Item] => s }

      onPhaseComplete("classify", Map(
        "successCount"    -> successes.size.toString,
        "failureCount"    -> failures.size.toString,
        "suspiciousCount" -> suspicious.size.toString
      ))

      SubBatchResult(batch.batchId, successes, failures, suspicious)
    }
  }

  private def handleAck(ack: AckResult, batchId: String): Future[Unit] = ack match {
    case AckReceived       => Future.successful(())
    case AckTimeout(ms)    => Future.failed(new IllegalStateException(
      s"[$batchId] External system ack timeout after ${ms}ms"))
    case AckRejected(msg)  => Future.failed(new IllegalStateException(
      s"[$batchId] External system rejected: $msg"))
  }

  private def handlePoll(result: PollResult, batchId: String): Future[ResponseFile] = result match {
    case ResponseReady(file) => Future.successful(file)
    case PollTimeout(attempts, ms) => Future.failed(new IllegalStateException(
      s"[$batchId] Response poll timeout after $attempts attempts (${ms}ms)"))
    case PollError(cause) => Future.failed(new IllegalStateException(
      s"[$batchId] Response poll error", cause))
  }
}
