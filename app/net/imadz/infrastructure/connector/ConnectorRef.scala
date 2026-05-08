package net.imadz.infrastructure.connector

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

/**
 * 外部系统连接器——网络 I/O 的标准抽象。与 Saga 解耦。
 *
 * 可被 SubBatchProcessor 直接调用，也可被 Saga Participant 调用。
 * 内建断路器、重试、超时由具体实现处理。
 */
trait ConnectorRef[-Req, +Res] {
  def execute(request: Req): Future[Res]
  def health: Future[ConnectorHealth]
}

case class ConnectorHealth(
    connectorId: String,
    isAvailable: Boolean,
    circuitBreakerState: String,
    lastFailure: Option[Throwable] = None
)

case class ConnectorSettings(
    maxRetries: Int = 3,
    requestTimeout: FiniteDuration,
    cbMaxFailures: Int = 5,
    cbResetTimeout: FiniteDuration
)
