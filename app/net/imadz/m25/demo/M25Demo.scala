package net.imadz.m25.demo

import net.imadz.m25.binding._
import net.imadz.m25.template._

import scala.concurrent.duration._

/**
 * ===== M2.5 Demonstration: Recharge + Purchase from One Template =====
 *
 * This demo shows how 12 Persistent FSM nodes (6 recharge + 6 purchase)
 * are generated from a single ExternalTwoPhaseChainTemplate, differing
 * only in their parameterization.
 *
 * == Before (M2 / Java) ==
 *   - 30 Java class files (6 sub-flows × 5 files × 2 chains)
 *   - ~5000 lines of boilerplate
 *   - Copy-paste with find-and-replace class names
 *
 * == After (M2.5 / Scala) ==
 *   - 1 template class (~500 lines, written once)
 *   - ~20 lines of configuration per chain
 *   - Compiler-verified parameter completeness
 *
 * == Design Principle ==
 *   - Template is Scaffold, not Framework:
 *     `materialize()` returns standard Akka types.
 *     You can always break the glass and modify generated FSMs directly.
 *   - Anti-Corruption Layer is a first-class boundary:
 *     GatewayRef abstracts over external systems; templates never
 *     import EAMS, P2B, SFTP, or SMS packages.
 */
object M25Demo {

  // ============================================================
  // Step 1: Wire up gateway references (Anti-Corruption Layer)
  // ============================================================
  //
  // In a real app, these come from ActorSystemContainer:
  //   val gwSftp = ActorGatewayRef(ActorSystemContainer.getFtpService)
  //   val gwCore = ActorGatewayRef(ActorSystemContainer.getCoreApiService)
  //   val gwP2b  = ActorGatewayRef(ActorSystemContainer.getP2BService)
  //
  // For this demo, they are abstract — the template only sees GatewayRef.

  // ============================================================
  // Step 2: Define the recharge chain
  // ============================================================
  def defineRechargeChain(
      gwSftp: GatewayRef[_],
      gwCore: GatewayRef[_],
      gwP2b:  GatewayRef[_]
  ): DAGSubgraph = {

    val template = new ExternalTwoPhaseChainTemplate

    template.materialize(
      ExternalTwoPhaseChainTemplate.Params(
        chainId  = "recharge",
        bindings = ChainExternalBindings(sftp = gwSftp, core = gwCore, p2b = gwP2b),
        messages = ChainMessages.RechargeSuccess,
        rules    = ChainBusinessRules(
          errorCodeMapper = {
            case "BALANCE_INSUFFICIENT" => FailureCategory.BalanceInsufficient
            case _                      => FailureCategory.UnknownError
          },
          p2bFlowType     = "Recharge",
          slaThreshold    = 10.minutes,
          releaseQuotaOnFailure = true
        )
      )
    )
  }

  // ============================================================
  // Step 3: Define the purchase chain — same template!
  // ============================================================
  def definePurchaseChain(
      gwSftp: GatewayRef[_],
      gwCore: GatewayRef[_],
      gwP2b:  GatewayRef[_]
  ): DAGSubgraph = {

    val template = new ExternalTwoPhaseChainTemplate

    template.materialize(
      ExternalTwoPhaseChainTemplate.Params(
        chainId  = "purchase",
        bindings = ChainExternalBindings(sftp = gwSftp, core = gwCore, p2b = gwP2b),
        messages = ChainMessages.PurchaseSuccess,
        rules    = ChainBusinessRules(
          errorCodeMapper = {
            case "QUOTA_EXCEEDED" => FailureCategory.QuotaExceeded
            case _                => FailureCategory.UnknownError
          },
          p2bFlowType     = "Purchase",
          slaThreshold    = 15.minutes,
          releaseQuotaOnFailure = true
        )
      )
    )
  }

  // ============================================================
  // Step 4: Resource guards (quota reserve + release)
  // ============================================================
  def defineResourceGuards(): DAGSubgraph = {
    val template = new ResourceGuardTemplate

    template.materialize(
      ResourceGuardTemplate.Params(
        resourceType    = "quota",
        timeout         = 30.minutes,
        releaseStrategy = ResourceGuardTemplate.ReleaseStrategy.TimeoutAutoRelease,
        cascadeTo       = Some(ResourceGuardTemplate.Params(
          resourceType    = "total-quota",
          timeout         = 30.minutes,
          releaseStrategy = ResourceGuardTemplate.ReleaseStrategy.ManualOnly
        ))
      )
    )
  }

  // ============================================================
  // Step 5: Notification relays (SMS success + reminder)
  // ============================================================
  def defineNotifications(): DAGSubgraph = {
    val template = new NotificationRelayTemplate

    template.materialize(
      NotificationRelayTemplate.Params(
        serviceName      = "sms",
        successTemplate  = NotificationRelayTemplate.MessageTemplate(
          title = "交易成功",
          body  = "尾号{{cardNo}}交易{{amount}}元已确认"
        ),
        failureTemplate  = NotificationRelayTemplate.MessageTemplate(
          title = "交易失败",
          body  = "尾号{{cardNo}}交易失败：{{reason}}"
        ),
        complianceWindow = (8, 20) // SMS only between 08:00-20:00
      )
    )
  }

  // ============================================================
  // Step 6: Batch orchestrator
  // ============================================================
  def defineBatchOrchestrator(): DAGSubgraph = {
    val template = new BatchOrchestratorTemplate

    template.materialize(
      BatchOrchestratorTemplate.Params(
        prefix        = "salary-saving",
        shardStrategy = BatchOrchestratorTemplate.ShardStrategy.default,
        retryPolicy   = BatchOrchestratorTemplate.RetryPolicy.default
      )
    )
  }

  // ============================================================
  // Step 7: Assemble the full DAG
  // ============================================================
  def assembleFullDAG(
      gwSftp: GatewayRef[_],
      gwCore: GatewayRef[_],
      gwP2b:  GatewayRef[_]
  ): DAGSubgraph = {

    val batch       = defineBatchOrchestrator()
    val guards      = defineResourceGuards()
    val recharge    = defineRechargeChain(gwSftp, gwCore, gwP2b)
    val purchase    = definePurchaseChain(gwSftp, gwCore, gwP2b)
    val notifs      = defineNotifications()

    // Merge all subgraphs into one
    DAGSubgraph(
      nodes = batch.nodes ++ guards.nodes ++ recharge.nodes ++ purchase.nodes ++ notifs.nodes,
      edges = batch.edges ++ guards.edges ++ recharge.edges ++ purchase.edges ++ notifs.edges
    )
  }

  // ============================================================
  // Stats for the M2.5 page
  // ============================================================
  def main(args: Array[String]): Unit = {
    println("=" * 60)
    println("  M2.5 FSM Template — Demonstration")
    println("=" * 60)
    println()
    println("  Templates available: 4")
    println("    - ExternalTwoPhaseChain (recharge/purchase/... )")
    println("    - ResourceGuard         (quota reserve/release)")
    println("    - NotificationRelay     (sms/reminder)")
    println("    - BatchOrchestrator     (job/pre-batch/re-batch/...)")
    println()
    println("  FSM nodes generated: 23")
    println("    ExternalTwoPhaseChain × 2 chains = 12 FSM")
    println("    ResourceGuard                    =  3 FSM")
    println("    NotificationRelay                =  2 FSM")
    println("    BatchOrchestrator                =  6 FSM")
    println()
    println("  Lines of Scala code:")
    println("    Template core (total)            = ~500 lines")
    println("    Per-chain configuration          =  ~15 lines")
    println()
    println("  Comparison with M2 (Java):")
    println("    Java class files (60)           →  4 template classes")
    println("    ~5,000 lines boilerplate        →  ~500 lines template + ~80 lines config")
    println("    Copy-paste with rename          →  Compiler-verified parameters")
    println()
  }
}
