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
    pipeline: SubBatchPipeline[Item, RawResult]
)(implicit ec: ExecutionContext) {

  /**
   * 处理一个小批次，返回三分类结果。
   */
  def process(batch: SubBatch[Item]): Future[SubBatchResult[Classification[Item]]] = {
    val ctx = batch.context

    for {
      // 1. 文件生成
      generatedFile <- pipeline.fileGen.generate(batch.items, ctx)

      // 2. 文件上传
      receipt <- pipeline.upload.upload(generatedFile, ctx)

      // 3. 等待外部系统确认
      ack <- pipeline.waitAck.waitForAck(receipt, ctx)
      _    <- handleAck(ack, batch.batchId)

      // 4. 轮询响应
      pollResult <- pipeline.pollResp.poll(ctx)
      responseFile <- handlePoll(pollResult, batch.batchId)

      // 5. 解析响应
      rawResults <- pipeline.parse.parse(responseFile, ctx)

      // 6. 分类
      classifications <- pipeline.classify.classify(rawResults, batch.items)

    } yield {
      val successes = classifications.collect { case s: Success[Item] => s }
      val failures  = classifications.collect { case f: Failure[Item] => f }
      val suspicious = classifications.collect { case s: Suspicious[Item] => s }

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
