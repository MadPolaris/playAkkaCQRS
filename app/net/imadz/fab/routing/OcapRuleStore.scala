package net.imadz.fab.routing

import java.util.concurrent.atomic.AtomicReference
import javax.inject.{Inject, Singleton}
import play.api.Configuration
import org.slf4j.LoggerFactory

/**
 * Thread-safe OCAP rule store that loads rules from HOCON configuration.
 *
 * Rules are defined in `conf/ocap-rules.conf` under the `ocap.rules` path.
 * Parsed expressions use [[ExpressionParser]]; action plans use a simple
 * configuration-driven parser.
 *
 * Thread safety: backed by [[java.util.concurrent.atomic.AtomicReference]]
 * so that [[reload]] (hot-reload from config) is an atomic swap.
 */
@Singleton
class OcapRuleStore @Inject()(config: Configuration) {

  private val log = LoggerFactory.getLogger(classOf[OcapRuleStore])

  private val rulesRef = new AtomicReference[List[OcapRuleDefinition]](loadRules())

  /** Return all loaded OCAP rules. */
  def getRules: List[OcapRuleDefinition] = rulesRef.get

  /** Return rules whose [[OcapRuleDefinition.routeId]] matches a given route ID. */
  def getRulesByRoute(routeId: String): List[OcapRuleDefinition] =
    rulesRef.get.filter(_.routeId == routeId)

  /** Hot-reload rules from current configuration. */
  def reload(): Unit = {
    rulesRef.set(loadRules())
    log.info(s"OCAP rules reloaded: ${rulesRef.get.size} rules")
  }

  // ── Private helpers ───────────────────────────────────────────────

  private def loadRules(): List[OcapRuleDefinition] = {
    val entries = config.getOptional[Seq[Configuration]]("ocap.rules").getOrElse(Seq.empty)
    entries.toList.flatMap { rc =>
      try {
        loadSingleRule(rc)
      } catch {
        case e: Exception =>
          log.warn(s"Skipping OCAP rule due to error: ${e.getMessage}")
          None
      }
    }
  }

  private def loadSingleRule(rc: Configuration): Option[OcapRuleDefinition] = {
    val conditionStr = rc.get[String]("condition")
    val condition = ExpressionParser(conditionStr) match {
      case Right(expr) => expr
      case Left(err) =>
        log.warn(s"Skipping OCAP rule '${rc.get[String]("ruleId")}': " +
          s"failed to parse condition '$conditionStr': $err")
        return None
    }
    Some(OcapRuleDefinition(
      ruleId = rc.get[String]("ruleId"),
      name = rc.get[String]("name"),
      triggerCondition = condition,
      actionPlan = parseAction(rc),
      priority = rc.getOptional[Int]("priority").getOrElse(0),
      maxTriggersPerLot = rc.getOptional[Int]("maxTriggersPerLot").getOrElse(3),
      onMissingMetric = rc.getOptional[String]("onMissingMetric") match {
        case Some("ConservativeReject") => ConservativeReject
        case _                          => ConservativePass
      },
      routeId = rc.getOptional[String]("routeId").getOrElse("")
    ))
  }

  private def parseAction(rc: Configuration): OcapActionPlan = {
    val tpe = rc.get[String]("action.type")
    tpe match {
      case "Hold"         => OcapHold(rc.get[Long]("action.durationMs"), rc.get[String]("action.reason"))
      case "Rework"       => OcapRework(rc.get[String]("action.recipeId"), rc.get[Int]("action.maxCount"))
      case "Scrap"        => OcapScrap(rc.get[String]("action.reason"))
      case "Notify"       => OcapNotify(rc.get[String]("action.reason"), rc.get[String]("action.escalationPath"))
      case "AdjustRecipe" => OcapAdjustRecipe(rc.get[String]("action.recipeId"), rc.get[Double]("action.offsetNm"))
      case other =>
        throw new IllegalArgumentException(s"Unknown OCAP action type: $other")
    }
  }
}
