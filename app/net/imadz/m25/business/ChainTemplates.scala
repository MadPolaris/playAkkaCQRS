package net.imadz.m25.business

import net.imadz.m25.component._

import scala.concurrent.duration._

/**
 * 预定义业务链路模板。
 *
 * 每条链路 = 同一套标准组件 + 不同的业务参数（ErrorCodeMapping / ReBatchPolicy / PhysicalConstraints）。
 *
 * 与 M2.5 模板 ExternalTwoPhaseChainTemplate 的对比：
 *   - M2.5:  new ExternalTwoPhaseChainTemplate().materialize(Params(chainId = "recharge", ...))
 *            → 生成 6 个 EventSourcedBehavior FSM
 *   - M2.5+: ChainTemplates.recharge(pipeline)
 *            → 组装标准组件 + 注入业务参数
 */
object ChainTemplates {

  // ============================================================
  // 充值链路
  // ============================================================

  def recharge[Item](
      pipeline: SubBatchPipeline[Item, Any],
      reconfirmHandler: Option[ReconfirmHandler[Item]] = None
  ): ChainDsl.ChainDefinition[Item] = {

    ChainDsl.define[Item]("recharge") { c =>
      c.fileGen(pipeline.fileGen)
      c.upload(pipeline.upload)
      c.waitAck(pipeline.waitAck)
      c.pollResp(pipeline.pollResp)
      c.parse(pipeline.parse)
      c.classify(
        ChainDsl.errorCodeClassifier[Any, Item](
          extractCodeFn = { raw => raw.toString.split("-").headOption.getOrElse("UNKNOWN") },
          associateFn = { (raw, items) => items.headOption }, // simplified
          mapping = ErrorCodeMapping(
            successCodes     = Set("OK"),
            failureCodes     = Map("BALANCE_INSUFFICIENT" -> NextStep.Scrap),
            suspiciousCodes  = Set("TIMEOUT", "NETWORK_ERROR")
          )
        ).asInstanceOf[ResultClassifier[Any, Item]]
      )

      reconfirmHandler.foreach(c.reconfirm)

      c.onFailure { r =>
        r.maxRetries(3)
        r.cooldown(5.minutes)
        r.when("BALANCE_INSUFFICIENT") { NextStep.Scrap }
        r.when("TIMEOUT")             { NextStep.RetrySameArea(5.minutes) }
        r.when("NETWORK_ERROR")       { NextStep.RetrySameArea(30.seconds) }
        r.otherwise                   { NextStep.ManualIntervention("UNKNOWN_ERROR") }
      }

      c.scheduling { s =>
        s.minBatchSize(1)
        s.maxBatchSize(100)
        s.batchWindow(10.minutes)
        s.allowMixedSources(true)
      }
    }
  }

  // ============================================================
  // 申购链路
  // ============================================================

  def purchase[Item](
      pipeline: SubBatchPipeline[Item, Any],
      reconfirmHandler: Option[ReconfirmHandler[Item]] = None
  ): ChainDsl.ChainDefinition[Item] = {

    ChainDsl.define[Item]("purchase") { c =>
      c.fileGen(pipeline.fileGen)
      c.upload(pipeline.upload)
      c.waitAck(pipeline.waitAck)
      c.pollResp(pipeline.pollResp)
      c.parse(pipeline.parse)
      c.classify(
        ChainDsl.errorCodeClassifier[Any, Item](
          extractCodeFn = { raw => raw.toString.split("-").headOption.getOrElse("UNKNOWN") },
          associateFn = { (raw, items) => items.headOption },
          mapping = ErrorCodeMapping(
            successCodes     = Set("OK"),
            failureCodes     = Map("QUOTA_EXCEEDED" -> NextStep.Scrap),
            suspiciousCodes  = Set("TIMEOUT", "PARTIAL")
          )
        ).asInstanceOf[ResultClassifier[Any, Item]]
      )

      reconfirmHandler.foreach(c.reconfirm)

      c.onFailure { r =>
        r.maxRetries(3)
        r.cooldown(5.minutes)
        r.when("QUOTA_EXCEEDED") { NextStep.Scrap }
        r.when("TIMEOUT")        { NextStep.RetrySameArea(5.minutes) }
        r.otherwise              { NextStep.ManualIntervention("UNKNOWN_ERROR") }
      }

      c.scheduling { s =>
        s.minBatchSize(1)
        s.maxBatchSize(100)
        s.batchWindow(10.minutes)
        s.allowMixedSources(true)
      }
    }
  }

  // ============================================================
  // Fab 设备区模板（Phase 3 使用）
  // ============================================================

  /**
   * Fab 设备区链路——通过 HTTP 协议与机台通信。
   *
   * 与充值/申购的区别：
   *   - 不经过 SFTP，而是直接 HTTP recipe 上传 + 结果拉取
   *   - 分类基于量测值范围（而非银行错误码）
   *   - 物理约束严格（FOUP 容量）
   */
  def equipmentArea[Item](
      areaId: String,
      pipeline: SubBatchPipeline[Item, Any],
      errorCodeMapping: ErrorCodeMapping,
      routerPolicy: ReBatchPolicy,
      constraints: PhysicalConstraints,
      reconfirmHandler: Option[ReconfirmHandler[Item]] = None
  ): ChainDsl.ChainDefinition[Item] = {

    ChainDsl.define[Item](areaId) { c =>
      c.fileGen(pipeline.fileGen)
      c.upload(pipeline.upload)
      c.waitAck(pipeline.waitAck)
      c.pollResp(pipeline.pollResp)
      c.parse(pipeline.parse)
      c.classify(
        ChainDsl.errorCodeClassifier[Any, Item](
          extractCodeFn = { raw => raw.toString.split("-").headOption.getOrElse("UNKNOWN") },
          associateFn = { (raw, items) => items.headOption },
          mapping = errorCodeMapping
        ).asInstanceOf[ResultClassifier[Any, Item]]
      )

      reconfirmHandler.foreach(c.reconfirm)

      c.onFailure { r =>
        r.maxRetries(routerPolicy.maxRetries)
        r.cooldown(routerPolicy.defaultCooldown)
        routerPolicy.actionMap.foreach { case (code, action) =>
          r.when(code) { action }
        }
      }

      c.scheduling { s =>
        s.minBatchSize(constraints.minBatchSize)
        s.maxBatchSize(constraints.maxBatchSize)
        s.carrierCapacity(constraints.carrierCapacity)
        s.batchWindow(constraints.batchWindow)
        s.allowMixedSources(constraints.allowMixedSources)
      }
    }
  }

  // ============================================================
  // 关键对比演示
  // ============================================================

  def main(args: Array[String]): Unit = {
    println("=" * 60)
    println("  M2.5+ ChainTemplates — Pre-built Business Chains")
    println("=" * 60)
    println()
    println("  Available templates:")
    println("    ChainTemplates.recharge(pipeline)")
    println("    ChainTemplates.purchase(pipeline)")
    println("    ChainTemplates.equipmentArea(areaId, pipeline, ...)  // Fab M3")
    println()
    println("  Key pattern:")
    println("    Same components, different ErrorCodeMapping + ReBatchPolicy")
    println("    recharge: success=OK, failure=BALANCE_INSUFFICIENT, suspicious=TIMEOUT")
    println("    purchase: success=OK, failure=QUOTA_EXCEEDED, suspicious=TIMEOUT|PARTIAL")
    println()
    println("  vs M2.5 template:")
    println("    M2.5:  new ExternalTwoPhaseChainTemplate().materialize(Params(chainId=...))")
    println("           → 563 lines of FSM code generation")
    println("    M2.5+: ChainTemplates.recharge(pipeline)")
    println("           → ~20 lines of business parameters")
  }
}
