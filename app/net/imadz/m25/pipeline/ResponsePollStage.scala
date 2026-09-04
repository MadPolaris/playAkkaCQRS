package net.imadz.m25.pipeline

import net.imadz.m25.component.{PollError, PollResult, PollTimeout, ResponseFile, ResponsePoller, ResponseReady}

import java.nio.file.{Files, Paths}
import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

/**
 * 响应轮询阶段——从外部系统拉取处理结果。
 *
 * 策略：
 *   - SFTP 目录轮询：检查远程目录是否有匹配模式的响应文件
 *   - 间隔 + 最大尝试次数控制超时
 */
class SftpPollStage(
    sftpRef: SftpConnectorRef,
    remoteDir: String,
    filePattern: String,
    localDownloadDir: String = System.getProperty("java.io.tmpdir"),
    pollInterval: FiniteDuration = 30.seconds,
    maxAttempts: Int = 20
)(implicit ec: ExecutionContext) extends ResponsePoller {

  override def poll(context: Map[String, Any]): Future[PollResult] = {
    val batchId = context.getOrElse("batchId", "unknown").toString

    attemptPoll(batchId, 0)
  }

  private def attemptPoll(batchId: String, attempt: Int): Future[PollResult] = {
    if (attempt >= maxAttempts) {
      return Future.successful(PollTimeout(maxAttempts, (maxAttempts * pollInterval).toMillis))
    }

    sftpRef.listFiles(remoteDir, filePattern).flatMap {
      case net.imadz.infrastructure.connector.SftpConnector.ListSuccess(files) =>
        files.headOption match {
          case Some(fileInfo) if !fileInfo.isDirectory =>
            // Found matching file — download it
            val localPath = Paths.get(localDownloadDir, fileInfo.name).toString
            Files.createDirectories(Paths.get(localDownloadDir))

            sftpRef.download(
              s"$remoteDir/${fileInfo.name}",
              localPath
            ).map {
              case net.imadz.infrastructure.connector.SftpConnector.DownloadSuccess(path, bytes) =>
                val content = Files.readAllBytes(Paths.get(path))
                ResponseReady(ResponseFile(path, fileInfo.name, bytes, content))
              case net.imadz.infrastructure.connector.SftpConnector.DownloadFailure(path, cause) =>
                PollError(cause)
            }

          case _ =>
            // No file yet — wait and retry
            Thread.sleep(pollInterval.toMillis)
            attemptPoll(batchId, attempt + 1)
        }

      case net.imadz.infrastructure.connector.SftpConnector.ListFailure(dir, cause) =>
        Future.successful(PollError(cause))
    }
  }
}

/**
 * 带重试退避的轮询策略。
 * 每次轮询间隔递增，避免对外部系统造成压力。
 */
class BackoffPollStage(
    delegate: ResponsePoller,
    initialDelay: FiniteDuration = 10.seconds,
    maxDelay: FiniteDuration = 120.seconds,
    backoffFactor: Double = 1.5
)(implicit ec: ExecutionContext) extends ResponsePoller {

  override def poll(context: Map[String, Any]): Future[PollResult] = {
    pollWithBackoff(context, 0, initialDelay)
  }

  private def pollWithBackoff(context: Map[String, Any], attempt: Int, delay: FiniteDuration): Future[PollResult] = {
    delegate.poll(context).flatMap {
      case ResponseReady(file) =>
        Future.successful(ResponseReady(file))
      case PollTimeout(attempts, ms) =>
        Future.successful(PollTimeout(attempts, ms))
      case PollError(cause) =>
        val nextDelay = (delay.toMillis * backoffFactor).toLong.millis
        val cappedDelay = if (nextDelay > maxDelay) maxDelay else nextDelay
        Thread.sleep(delay.toMillis)
        pollWithBackoff(context, attempt + 1, cappedDelay)
    }
  }
}
