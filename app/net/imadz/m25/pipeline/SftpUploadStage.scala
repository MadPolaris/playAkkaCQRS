package net.imadz.m25.pipeline

import net.imadz.infrastructure.connector.SftpConnector
import net.imadz.m25.component.{FileUploader, GeneratedFile, UploadReceipt}

import scala.concurrent.{ExecutionContext, Future}

/**
 * SFTP 上传阶段——将本地文件上传到远程 SFTP 服务器。
 *
 * 通过 ConnectorRef 抽象调用，不直接依赖具体的 SSH 库。
 */
class SftpUploadStage(
    remoteDir: String,
    connectorRef: SftpConnectorRef
)(implicit ec: ExecutionContext) extends FileUploader {

  override def upload(file: GeneratedFile, context: Map[String, Any]): Future[UploadReceipt] = {
    val remotePath = if (remoteDir.endsWith("/")) s"$remoteDir${file.fileName}"
                     else s"$remoteDir/${file.fileName}"

    connectorRef.upload(file.localPath, remotePath).map {
      case SftpConnector.UploadSuccess(path, bytes) =>
        UploadReceipt(path, bytes, System.currentTimeMillis())
      case SftpConnector.UploadFailure(path, cause) =>
        throw new IllegalStateException(s"SFTP upload failed: $path", cause)
    }
  }
}

/**
 * SFTP 连接器引用——解耦 Connector Actor 的 ActorRef。
 * 在生产环境中通过 AkkaConnectorFactory 创建。
 */
trait SftpConnectorRef {
  def upload(localPath: String, remotePath: String): Future[SftpConnector.UploadResult]
  def download(remotePath: String, localPath: String): Future[SftpConnector.DownloadResult]
  def listFiles(remoteDir: String, pattern: String): Future[SftpConnector.ListResult]
}

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout

/**
 * 基于 Akka Actor 的 SFTP 连接器引用。
 */
class AkkaSftpConnectorRef(
    ref: ActorRef[SftpConnector.Command]
)(implicit timeout: Timeout, scheduler: akka.actor.typed.Scheduler) extends SftpConnectorRef {

  override def upload(localPath: String, remotePath: String): Future[SftpConnector.UploadResult] =
    ref.ask[SftpConnector.UploadResult](SftpConnector.UploadFile(localPath, remotePath, _))

  override def download(remotePath: String, localPath: String): Future[SftpConnector.DownloadResult] =
    ref.ask[SftpConnector.DownloadResult](SftpConnector.DownloadFile(remotePath, localPath, _))

  override def listFiles(remoteDir: String, pattern: String): Future[SftpConnector.ListResult] =
    ref.ask[SftpConnector.ListResult](SftpConnector.ListFiles(remoteDir, pattern, _))
}
