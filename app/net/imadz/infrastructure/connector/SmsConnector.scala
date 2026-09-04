package net.imadz.infrastructure.connector

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.pattern.CircuitBreaker

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * SMS 连接器——包装短信网关 SDK，内建 CircuitBreaker。
 *
 * 支持合规时间窗口检查（如仅 08:00-20:00 发送）。
 */
object SmsConnector {

  sealed trait Command
  final case class SendSms(
      phoneNumber: String,
      title: String,
      body: String,
      replyTo: ActorRef[SendResult]
  ) extends Command
  final case class CheckHealth(replyTo: ActorRef[ConnectorHealth]) extends Command

  sealed trait SendResult
  final case class SendSuccess(messageId: String) extends SendResult
  final case class SendFailure(cause: Throwable) extends SendResult
  final case class ComplianceBlocked(reason: String) extends SendResult

  def apply(
      connectorId: String,
      settings: ConnectorSettings,
      smsImpl: SmsImplementation,
      compliance: ComplianceWindow = ComplianceWindow.default
  ): Behavior[Command] = Behaviors.setup { ctx =>
    new SmsConnectorBehavior(connectorId, settings, smsImpl, compliance, ctx).start()
  }

  /** 合规时间窗口 */
  case class ComplianceWindow(startHour: Int, endHour: Int) {
    def isWithinWindow: Boolean = {
      val hour = java.time.LocalTime.now().getHour
      hour >= startHour && hour < endHour
    }
  }
  object ComplianceWindow {
    val default: ComplianceWindow = ComplianceWindow(8, 20)
  }

  /** 抽象 SMS 实现 */
  trait SmsImplementation {
    def send(phoneNumber: String, title: String, body: String)
            (implicit ec: ExecutionContext): Future[SendResult]
  }
}

private class SmsConnectorBehavior(
    connectorId: String,
    settings: ConnectorSettings,
    smsImpl: SmsConnector.SmsImplementation,
    compliance: SmsConnector.ComplianceWindow,
    ctx: ActorContext[SmsConnector.Command]
) {
  import SmsConnector._

  private implicit val ec: ExecutionContext = ctx.executionContext

  private val circuitBreaker = new CircuitBreaker(
    ctx.system.classicSystem.scheduler,
    maxFailures = settings.cbMaxFailures,
    callTimeout = settings.requestTimeout,
    resetTimeout = settings.cbResetTimeout
  )

  private var lastFailure: Option[Throwable] = None

  def start(): Behavior[Command] = Behaviors.receiveMessagePartial {
    case SendSms(phone, title, body, replyTo) =>
      if (!compliance.isWithinWindow) {
        replyTo ! ComplianceBlocked(
          s"SMS window: ${compliance.startHour}:00-${compliance.endHour}:00, now: ${java.time.LocalTime.now()}"
        )
      } else {
        circuitBreaker.withCircuitBreaker(smsImpl.send(phone, title, body)).onComplete {
          case Success(result) =>
            lastFailure = None
            replyTo ! result
          case Failure(ex) =>
            lastFailure = Some(ex)
            replyTo ! SendFailure(ex)
        }
      }
      Behaviors.same

    case CheckHealth(replyTo) =>
      replyTo ! ConnectorHealth(
        connectorId = connectorId,
        isAvailable = !circuitBreaker.isOpen,
        circuitBreakerState = if (circuitBreaker.isOpen) "OPEN"
                              else if (circuitBreaker.isHalfOpen) "HALF-OPEN"
                              else "CLOSED",
        lastFailure = lastFailure
      )
      Behaviors.same
  }
}
