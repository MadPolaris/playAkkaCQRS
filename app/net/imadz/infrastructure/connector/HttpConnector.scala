package net.imadz.infrastructure.connector

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import akka.pattern.CircuitBreaker

import scala.concurrent.{ExecutionContext, Future}

/**
 * HTTP 连接器——包装 Akka HTTP，内建 CircuitBreaker。
 *
 * 在生产环境中，Akka HTTP 的具体实现（HttpRequest/HttpResponse）
 * 通过 AkkaConnectorFactory 注入。这里定义 Actor 协议和基础行为。
 */
object HttpConnector {

  sealed trait Command
  final case class Execute(
      method: String,
      url: String,
      headers: Map[String, String],
      body: Option[Array[Byte]],
      replyTo: ActorRef[Response]
  ) extends Command
  final case class CheckHealth(replyTo: ActorRef[ConnectorHealth]) extends Command

  sealed trait Response
  final case class Success(status: Int, body: Array[Byte]) extends Response
  final case class Failure(status: Int, message: String) extends Response
  final case class NetworkError(cause: Throwable) extends Response

  def apply(
      connectorId: String,
      settings: ConnectorSettings,
      httpImpl: HttpImplementation
  ): Behavior[Command] = Behaviors.setup { ctx =>
    Behaviors.withTimers { timers =>
      new HttpConnectorBehavior(connectorId, settings, httpImpl, ctx, timers).start()
    }
  }

  /** 抽象 HTTP 实现——解耦具体 HTTP 库 */
  trait HttpImplementation {
    def execute(method: String, url: String, headers: Map[String, String],
                body: Option[Array[Byte]])(implicit ec: ExecutionContext): Future[Response]
  }
}

private class HttpConnectorBehavior(
    connectorId: String,
    settings: ConnectorSettings,
    httpImpl: HttpConnector.HttpImplementation,
    ctx: ActorContext[HttpConnector.Command],
    timers: TimerScheduler[HttpConnector.Command]
) {
  import HttpConnector._

  private implicit val ec: ExecutionContext = ctx.executionContext

  private val circuitBreaker = new CircuitBreaker(
    ctx.system.classicSystem.scheduler,
    maxFailures = settings.cbMaxFailures,
    callTimeout = settings.requestTimeout,
    resetTimeout = settings.cbResetTimeout
  )

  private var failureCount: Int = 0
  private var lastFailure: Option[Throwable] = None

  circuitBreaker.onOpen {
    ctx.log.warn(s"[$connectorId] CircuitBreaker OPEN")
    notifyHealthChange()
  }
  circuitBreaker.onHalfOpen {
    ctx.log.info(s"[$connectorId] CircuitBreaker HALF-OPEN")
  }
  circuitBreaker.onClose {
    ctx.log.info(s"[$connectorId] CircuitBreaker CLOSED")
    failureCount = 0
    lastFailure = None
    notifyHealthChange()
  }

  def start(): Behavior[Command] = Behaviors.receiveMessagePartial {
    case Execute(method, url, headers, body, replyTo) =>
      executeRequest(method, url, headers, body, replyTo)
      Behaviors.same
    case CheckHealth(replyTo) =>
      replyTo ! currentHealth
      Behaviors.same
  }

  private def executeRequest(method: String, url: String, headers: Map[String, String],
                              body: Option[Array[Byte]], replyTo: ActorRef[Response]): Unit = {
    val eventualResponse = circuitBreaker.withCircuitBreaker(
      httpImpl.execute(method, url, headers, body)
    )

    eventualResponse.onComplete {
      case scala.util.Success(resp) =>
        replyTo ! resp
      case scala.util.Failure(ex) =>
        failureCount += 1
        lastFailure = Some(ex)
        replyTo ! NetworkError(ex)
    }
  }

  private def currentHealth: ConnectorHealth = ConnectorHealth(
    connectorId = connectorId,
    isAvailable = !circuitBreaker.isOpen,
    circuitBreakerState = if (circuitBreaker.isOpen) "OPEN"
                          else if (circuitBreaker.isHalfOpen) "HALF-OPEN"
                          else "CLOSED",
    lastFailure = lastFailure
  )

  private def notifyHealthChange(): Unit = {
    // In production: publish to event stream for monitoring
    ctx.log.info(s"[$connectorId] Health: ${currentHealth.circuitBreakerState}")
  }
}
