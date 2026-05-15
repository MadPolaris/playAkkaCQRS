package net.imadz.m25.component

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

/**
 * 失败项智能路由器——Process Manager 模式。
 *
 * M2 的 ReBatchActor 只做一件事：扫描失败 → 重新注入 Worker。
 * M2.5+ 的 ReBatchRouter 是可配置的决策引擎，根据失败原因 +
 * 上下文信息决定下一步工序的目标设备区。
 *
 * For Fab M3: 可以决定 rework、换设备区、换 recipe、报废、或人工介入。
 */

trait ReBatchRouter[Item] {
  /**
   * 对失败项进行路由决策。
   *
   * @param failures 失败项列表（含失败原因）
   * @param context  处理上下文（如重试次数、当前设备区等）
   * @return 每个失败项的路由决策
   */
  def route(
      failures: Seq[Failure[Item]],
      context: ProcessContext
  ): Future[Seq[RoutingDecision[Item]]]
}

case class RoutingDecision[Item](
    item: Item,
    nextStep: NextStep,
    reason: String
)

/** 下一步动作 */
sealed trait NextStep
object NextStep {
  /** 在同一设备区重试，延迟指定时间 */
  final case class RetrySameArea(delay: FiniteDuration) extends NextStep
  /** 路由到指定设备区，可选指定 recipe */
  final case class RouteToArea(areaId: String, recipeId: Option[String] = None) extends NextStep
  /** 需要人工介入，生成工单 */
  final case class ManualIntervention(ticketId: String) extends NextStep
  /** 报废 */
  case object Scrap extends NextStep
}

/** 处理上下文——包含便于路由决策的元数据 */
case class ProcessContext(
    /** 当前设备区 ID */
    currentAreaId: String,
    /** 当前 item 已重试次数 */
    retryCount: Int = 0,
    /** 原始批次 ID（用于合批追踪） */
    originalBatchId: Option[String] = None,
    /** 附加元数据 */
    metadata: Map[String, Any] = Map.empty
)

/** 可配置的路由策略——业务参数化 */
case class ReBatchPolicy(
    /** 最大重试次数，超过后升级到 ManualIntervention */
    maxRetries: Int = 3,
    /** 错误码 → 下一步动作的默认映射 */
    actionMap: Map[String, NextStep] = Map.empty,
    /** 默认等待时间后再重新成批 */
    defaultCooldown: FiniteDuration
)

object ReBatchPolicy {
  import scala.concurrent.duration._

  val salarySavingDefault: ReBatchPolicy = ReBatchPolicy(
    maxRetries = 3,
    actionMap = Map(
      "BALANCE_INSUFFICIENT" -> NextStep.Scrap,
      "QUOTA_EXCEEDED"       -> NextStep.Scrap,
      "TIMEOUT"              -> NextStep.RetrySameArea(5.minutes),
      "NETWORK_ERROR"        -> NextStep.RetrySameArea(30.seconds)
    ),
    defaultCooldown = 5.minutes
  )
}

/** 基于策略的路由器实现 */
class PolicyBasedReBatchRouter[Item](policy: ReBatchPolicy) extends ReBatchRouter[Item] {

  override def route(
      failures: Seq[Failure[Item]],
      context: ProcessContext
  ): Future[Seq[RoutingDecision[Item]]] = {

    val decisions = failures.map { f =>
      val effectiveRetry = context.retryCount

      if (effectiveRetry >= policy.maxRetries) {
        RoutingDecision(f.item,
          NextStep.ManualIntervention(s"MAX_RETRY_EXCEEDED-${f.reason.code}"),
          s"Exceeded max retries (${policy.maxRetries}): ${f.reason.message}")
      } else {
        val nextStep = f.reason.suggestedAction.orElse(
          policy.actionMap.get(f.reason.code)
        ).getOrElse(NextStep.RetrySameArea(policy.defaultCooldown))

        RoutingDecision(f.item, nextStep, f.reason.message)
      }
    }

    Future.successful(decisions)
  }
}
