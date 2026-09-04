package net.imadz.m25.component

import scala.concurrent.Future

/**
 * 结果三分类——将外部系统返回的结果按业务规则分为成功、失败、可疑三类。
 *
 * 成功和失败直接分流；可疑项进入 ReconfirmHandler 确定最终状态。
 */

sealed trait Classification[+Item]
final case class Success[Item](item: Item, rawResult: Any) extends Classification[Item]
final case class Failure[Item](item: Item, reason: FailureReason) extends Classification[Item]
final case class Suspicious[Item](item: Item, reason: SuspiciousReason) extends Classification[Item]

case class FailureReason(
    code: String,
    message: String,
    suggestedAction: Option[NextStep] = None
)

case class SuspiciousReason(
    code: String,
    message: String
)

/** 结果分类器——将 RawResult 按业务规则映射到对应 Item 的分类 */
trait ResultClassifier[RawResult, Item] {
  def classify(rawResults: Seq[RawResult], items: Seq[Item]): Future[Seq[Classification[Item]]]
}

/** 业务可配置的错误码映射 */
case class ErrorCodeMapping(
    /** 成功码——这些码对应的 item 直接标记为成功 */
    successCodes: Set[String],
    /** 失败码 + 建议的下一步动作 */
    failureCodes: Map[String, NextStep],
    /** 可疑码——这些码对应的 item 需要复核 */
    suspiciousCodes: Set[String]
)

object ErrorCodeMapping {
  val empty: ErrorCodeMapping = ErrorCodeMapping(
    successCodes = Set("OK", "SUCCESS"),
    failureCodes = Map.empty,
    suspiciousCodes = Set("TIMEOUT", "PARTIAL", "NETWORK_ERROR")
  )
}

/** 基于错误码的分类器实现——通过 ErrorCodeMapping 进行精确匹配 */
abstract class ErrorCodeBasedClassifier[RawResult, Item] extends ResultClassifier[RawResult, Item] {
  def errorCodeMapping: ErrorCodeMapping
  def extractCode(rawResult: RawResult): String
  def associateItem(rawResult: RawResult, items: Seq[Item]): Option[Item]

  override def classify(rawResults: Seq[RawResult], items: Seq[Item]): Future[Seq[Classification[Item]]] = {
    val results = rawResults.flatMap { raw =>
      val code = extractCode(raw)
      associateItem(raw, items).map { item =>
        if (errorCodeMapping.successCodes.contains(code))
          Success(item, raw)
        else if (errorCodeMapping.suspiciousCodes.contains(code))
          Suspicious(item, SuspiciousReason(code, s"Suspicious result: $code"))
        else
          Failure(item, FailureReason(code, s"Failed with code: $code",
            errorCodeMapping.failureCodes.get(code)))
      }
    }
    Future.successful(results)
  }
}
