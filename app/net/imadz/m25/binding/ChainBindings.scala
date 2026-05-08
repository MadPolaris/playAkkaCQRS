package net.imadz.m25.binding

import scala.concurrent.duration.FiniteDuration

/**
 * External gateway bindings for a two-phase business chain.
 *
 * Every ExternalTwoPhaseChain needs exactly three external touch-points:
 *   - sftp: file upload/download with the bank
 *   - core:  balance / transaction verification API
 *   - p2b:   wealth management platform notification
 */
final case class ChainExternalBindings(
    sftp: GatewayRef[_],
    core: GatewayRef[_],
    p2b:  GatewayRef[_]
)

/**
 * SMS message templates for the success and failure outcomes of a chain.
 */
final case class ChainMessages(
    successSmsTitle: String,
    successSmsBody:  String,
    failureSmsTitle: String,
    failureSmsBody:  String
)

object ChainMessages {
  val RechargeSuccess: ChainMessages = ChainMessages(
    successSmsTitle = "充值成功",
    successSmsBody  = "尾号{cardNo}充值{amount}元已到账",
    failureSmsTitle = "充值失败",
    failureSmsBody  = "尾号{cardNo}充值失败：{reason}"
  )
  val PurchaseSuccess: ChainMessages = ChainMessages(
    successSmsTitle = "申购成功",
    successSmsBody  = "尾号{cardNo}申购{amount}元已确认",
    failureSmsTitle = "申购失败",
    failureSmsBody  = "尾号{cardNo}申购失败：{reason}"
  )
}

/**
 * Business rules that vary between different chains sharing the same topology.
 *
 * These are the *only* things that differ between, say, recharge and purchase.
 * Everything else (state machine, timeout handling, DAG topology) is identical
 * and belongs in the template.
 */
final case class ChainBusinessRules(
    /** Maps external-system error codes to internal failure categories. */
    errorCodeMapper: String => FailureCategory,
    /** The P2B flow-type enum value for this business operation. */
    p2bFlowType:     String,
    /** Maximum time allowed for the entire chain before SLA violation. */
    slaThreshold:    FiniteDuration,
    /** Whether this chain triggers quota release on failure. */
    releaseQuotaOnFailure: Boolean = true
)

sealed trait FailureCategory
object FailureCategory {
  case object BalanceInsufficient extends FailureCategory
  case object QuotaExceeded      extends FailureCategory
  case object ExternalTimeout    extends FailureCategory
  case object UnknownError       extends FailureCategory
}
