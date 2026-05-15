package net.imadz.m25.component

import scala.concurrent.Future

/**
 * 可疑项复核处理器。
 *
 * 仅对分类为"可疑"的 item 执行复核，确定最终状态。
 * 这不是流水线的必经阶段——成功和失败项不经过这里。
 *
 * 典型场景：
 *   - 银行返回 "TIMEOUT" → 通过 Core API 二次查证实际交易状态
 *   - 量测值在规格边界 → 重新量测或使用更高精度设备复核
 *   - 文件解析部分损坏 → 尝试从备份路径重新下载
 */
trait ReconfirmHandler[Item] {
  /**
   * 对可疑项执行复核，返回确定后的分类。
   * 复核后不应再有可疑——必须确定是成功还是失败。
   */
  def reconfirm(suspicious: Seq[Suspicious[Item]]): Future[Seq[Classification[Item]]]
}

/**
 * 基于外部查证的复核处理器。
 *
 * 通过连接器向权威数据源（如 Core API）查证，确定可疑项的真实状态。
 */
abstract class VerifyingReconfirmHandler[Item] extends ReconfirmHandler[Item] {

  /** 查证可疑项的实际状态——子类实现具体查证逻辑 */
  def verify(item: Item, reason: SuspiciousReason): Future[VerificationResult]

  sealed trait VerificationResult
  case object VerifiedSuccess extends VerificationResult
  final case class VerifiedFailure(failureReason: FailureReason) extends VerificationResult
  final case class StillUncertain(reason: String) extends VerificationResult

  override def reconfirm(suspicious: Seq[Suspicious[Item]]): Future[Seq[Classification[Item]]] = {
    import scala.concurrent.ExecutionContext.Implicits.global

    val futures = suspicious.map { s =>
      verify(s.item, s.reason).map {
        case VerifiedSuccess =>
          Success(s.item, s.reason)
        case VerifiedFailure(fr) =>
          Failure(s.item, fr)
        case StillUncertain(msg) =>
          // 仍不确定 → 保守处理：标记为失败，走 ReBatchRouter
          Failure(s.item, FailureReason(s.reason.code, s"Uncertain after verify: $msg", None))
      }
    }

    Future.sequence(futures)
  }
}
