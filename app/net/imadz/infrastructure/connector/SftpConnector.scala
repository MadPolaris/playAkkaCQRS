package net.imadz.infrastructure.connector

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.pattern.CircuitBreaker

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * SFTP 连接器——包装 SSH/SFTP 客户端，内建 CircuitBreaker。
 *
 * 抽象 SFTP 操作为统一接口，具体实现（如 Apache MINA SSHD）通过 SftpImplementation 注入。
 */
object SftpConnector {

  sealed trait Command
  final case class UploadFile(localPath: String, remotePath: String, replyTo: ActorRef[UploadResult]) extends Command
  final case class DownloadFile(remotePath: String, localPath: String, replyTo: ActorRef[DownloadResult]) extends Command
  final case class ListFiles(remoteDir: String, pattern: String, replyTo: ActorRef[ListResult]) extends Command
  final case class DeleteFile(remotePath: String, replyTo: ActorRef[DeleteResult]) extends Command
  final case class CheckHealth(replyTo: ActorRef[ConnectorHealth]) extends Command

  sealed trait UploadResult
  final case class UploadSuccess(remotePath: String, bytesTransferred: Long) extends UploadResult
  final case class UploadFailure(path: String, cause: Throwable) extends UploadResult

  sealed trait DownloadResult
  final case class DownloadSuccess(localPath: String, bytesTransferred: Long) extends DownloadResult
  final case class DownloadFailure(path: String, cause: Throwable) extends DownloadResult

  sealed trait ListResult
  final case class ListSuccess(files: Seq[SftpFileInfo]) extends ListResult
  final case class ListFailure(dir: String, cause: Throwable) extends ListResult

  final case class SftpFileInfo(name: String, size: Long, lastModified: Long, isDirectory: Boolean)

  sealed trait DeleteResult
  final case class DeleteSuccess(path: String) extends DeleteResult
  final case class DeleteFailure(path: String, cause: Throwable) extends DeleteResult

  def apply(
      connectorId: String,
      settings: ConnectorSettings,
      sftpImpl: SftpImplementation
  ): Behavior[Command] = Behaviors.setup { ctx =>
    new SftpConnectorBehavior(connectorId, settings, sftpImpl, ctx).start()
  }

  /** 抽象 SFTP 实现——解耦具体 SSH 库 */
  trait SftpImplementation {
    def upload(localPath: String, remotePath: String)(implicit ec: ExecutionContext): Future[UploadResult]
    def download(remotePath: String, localPath: String)(implicit ec: ExecutionContext): Future[DownloadResult]
    def listFiles(remoteDir: String, pattern: String)(implicit ec: ExecutionContext): Future[ListResult]
    def delete(remotePath: String)(implicit ec: ExecutionContext): Future[DeleteResult]
    def connect(): Future[Unit]
    def disconnect(): Future[Unit]
  }
}

private class SftpConnectorBehavior(
    connectorId: String,
    settings: ConnectorSettings,
    sftpImpl: SftpConnector.SftpImplementation,
    ctx: ActorContext[SftpConnector.Command]
) {
  import SftpConnector._

  private implicit val ec: ExecutionContext = ctx.executionContext

  private val circuitBreaker = new CircuitBreaker(
    ctx.system.classicSystem.scheduler,
    maxFailures = settings.cbMaxFailures,
    callTimeout = settings.requestTimeout,
    resetTimeout = settings.cbResetTimeout
  )

  private var lastFailure: Option[Throwable] = None

  circuitBreaker.onOpen {
    ctx.log.warn(s"[$connectorId] SFTP CircuitBreaker OPEN")
    sftpImpl.disconnect()
  }
  circuitBreaker.onClose {
    ctx.log.info(s"[$connectorId] SFTP CircuitBreaker CLOSED")
    sftpImpl.connect()
  }

  def start(): Behavior[Command] = {
    // Connect on startup
    sftpImpl.connect().onComplete {
      case Success(_) => ctx.log.info(s"[$connectorId] SFTP connected")
      case Failure(ex) => ctx.log.error(s"[$connectorId] SFTP connection failed", ex)
    }

    Behaviors.receiveMessagePartial {
      case UploadFile(local, remote, replyTo) =>
        withCB(sftpImpl.upload(local, remote), replyTo, (e: Throwable) => UploadFailure(remote, e))
        Behaviors.same
      case DownloadFile(remote, local, replyTo) =>
        withCB(sftpImpl.download(remote, local), replyTo, (e: Throwable) => DownloadFailure(remote, e))
        Behaviors.same
      case ListFiles(dir, pattern, replyTo) =>
        withCB(sftpImpl.listFiles(dir, pattern), replyTo, (e: Throwable) => ListFailure(dir, e))
        Behaviors.same
      case DeleteFile(path, replyTo) =>
        withCB(sftpImpl.delete(path), replyTo, (e: Throwable) => DeleteFailure(path, e))
        Behaviors.same
      case CheckHealth(replyTo) =>
        val isOpen = circuitBreaker.isOpen
        replyTo ! ConnectorHealth(
          connectorId = connectorId,
          isAvailable = !isOpen,
          circuitBreakerState = if (isOpen) "OPEN" else if (circuitBreaker.isHalfOpen) "HALF-OPEN" else "CLOSED",
          lastFailure = lastFailure
        )
        Behaviors.same
    }
  }

  private def withCB[R](future: Future[R], replyTo: ActorRef[R], errorMapper: Throwable => R): Unit = {
    circuitBreaker.withCircuitBreaker(future).onComplete {
      case Success(result) =>
        lastFailure = None
        replyTo ! result
      case Failure(ex) =>
        lastFailure = Some(ex)
        replyTo ! errorMapper(ex)
    }
  }
}
