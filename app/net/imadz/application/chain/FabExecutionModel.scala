package net.imadz.application.chain

import akka.actor.typed.ActorRef
import net.imadz.application.aggregates.LotProtocol.{LotCommand, LotConfirmation}
import net.imadz.application.services.transactor.FabSagaProtocol.FabSagaConfirmation
import net.imadz.common.CborSerializable
import net.imadz.common.CommonTypes.Id
import net.imadz.domain.events.FabSimulationEvent
import net.imadz.fab.protocol.ActorEquipmentAdapter
import net.imadz.domain.routing.{OcapActionPlan, OcapRuleDefinition}
import net.imadz.application.scenario.FabSimulationScenario

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NoStackTrace

/**
 * Shared execution model types used by FabDemoPipeline, FabScenarioPipeline,
 * and FabFlowEngine (the three PorExecutor pipeline functions).
 *
 * Extracted from [[net.imadz.application.chain.FabDemoPipeline]] to remove circular-ish
 * import dependencies where the other two engines imported from FabDemoPipeline
 * solely for these type definitions.
 */
object FabExecutionModel {

  case class WaferInfo(
    waferId: String,
    reworkCount: Int = 0,
    cdValueHistory: List[Double] = Nil,
    classification: Option[String] = None,
    subLot: Option[String] = None,
    measurements: Map[String, List[Double]] = Map.empty
  ) extends CborSerializable

  case class EquipmentState(
    equipmentId: String,
    status: String = "Idle",
    errorCount: Int = 0
  ) extends CborSerializable

  case class FabDemoState(
    wafers: Map[String, WaferInfo],
    passCount: Int = 0,
    scrapCount: Int = 0,
    iteration: Int = 0,
    ledgerSeq: Int = 0,
    pilotPassed: Boolean = false,
    reviewApproved: Boolean = false,
    spawnedChildLotKey: Option[String] = None,
    /** Index into Por.steps (0-based) */
    currentRoutingStep: Int = 0,
    /** Reentry count per equipment area visited so far */
    routingStepReentry: Map[String, Int] = Map.empty,
    /** Ordered list of area IDs visited (for reentry calculation) */
    areaVisitHistory: List[String] = Nil,
    /** Current equipment area or transport path (for aggregate state panel display) */
    currentArea: String = "STOCKER",
    /** Child lot view descriptor: suffix -> (status, waferCount). Set at split, transitioned at merge. */
    childLotView: Map[String, (String, Int)] = Map.empty,
    /** Equipment-level state for OCAP equipment conditions */
    equipmentState: Map[String, EquipmentState] = Map.empty,
    /** OCAP actions pending execution, populated by OcapEngine.evaluate,
      * consumed by OcapActionRouter. (ruleId, actionPlan) in priority order. */
    ocapActions: List[(String, OcapActionPlan)] = Nil
  ) extends CborSerializable

  /** Demo-specific execution profile — bundles scenario config + simulation tuning knobs.
   *  Separated from [[ExecutionContext]] so kernel code (PipelineStages, FabFlowEngine)
   *  doesn't import scenario types directly. */
  case class DemoExecutionProfile(
    scenario: FabSimulationScenario,
    speedMultiplier: Double = 1.0,
    faultProbability: Double = 0.0
  )

  case class FabDemoContext(
    scenario: FabSimulationScenario,
    foupId: String,
    lotRef: akka.cluster.sharding.typed.scaladsl.EntityRef[LotCommand],
    /** @deprecated Prefer `childLotRefs("rwk")` — SubLot context switching uses the child maps. */
    reworkLotRef: akka.cluster.sharding.typed.scaladsl.EntityRef[LotCommand],
    waferUUIDs: Map[String, Id],
    sourceLotId: Id,
    /** @deprecated Prefer `childLotIds("rwk")` — SubLot context switching uses the child maps. */
    reworkLotId: Id,
    adapter: ActorEquipmentAdapter,
    /** Target for Phase 2-3 removal: UI events should flow via Projection, not direct push. */
    publisher: FabSimulationEvent => Unit,
    ignoreLotReply: ActorRef[LotConfirmation],
    sagaTx: (Id, Id, Set[Id], Set[String], Option[Id]) => Future[FabSagaConfirmation],
    speedMultiplier: Double,
    scrapLotRef: Option[akka.cluster.sharding.typed.scaladsl.EntityRef[LotCommand]] = None,
    scrapLotId: Option[Id] = None,
    childLotRefs: Map[String, akka.cluster.sharding.typed.scaladsl.EntityRef[LotCommand]] = Map.empty,
    childLotIds: Map[String, Id] = Map.empty,
    ocapRules: List[OcapRuleDefinition] = Nil,
    faultProbability: Double = 0.0,
    /** P0 staleness guard: returns false once this pipeline run's generation is superseded
      * (crash recovery started a new run). Stale runs must not publish UI events or drive
      * further side effects — see [[net.imadz.application.chain.PipelineRunRegistry]]. */
    runToken: () => Boolean = () => true
  )(implicit val ec: ExecutionContext) extends net.imadz.application.chain.ExecutionContext {

    /** Guarded publish: drops events from superseded pipeline runs. */
    def publish(event: FabSimulationEvent): Unit = if (runToken()) publisher(event)

    /** True when this run has been superseded by a newer generation. */
    def stale: Boolean = !runToken()

    /** Convenience accessor for the demo profile (for gradual migration). */
    def profile: DemoExecutionProfile = DemoExecutionProfile(scenario, speedMultiplier, faultProbability)

    /** Runtime-only Promise storage for AwaitSubLotResult suspension.
     *  Keyed by lotKey ("rwk","pilot", etc.). NOT serialized — re-populated at runtime and on recovery. */
    val awaitPromises: scala.collection.concurrent.TrieMap[String, scala.concurrent.Promise[SubLotResult]] =
      scala.collection.concurrent.TrieMap.empty

    /** Intra-stage progress hook. Default routes through publisher → WebSocket (non-Actor path).
     *  The Actor path overrides this to persist [[StageProgress]] events via the journal.
     *
     *  @demo The default fallback publishes GlobalStatusChanged directly for backward
     *        compat with non-Actor callers (tests, direct invocation). In the unified Actor
     *        path this is overridden to go through the journal. */
    var stageProgressFn: (String, String, String) => Unit =
      (status, detail, phase) => publish(net.imadz.domain.events.GlobalStatusChanged(status, detail, phase))

    override def stageProgress(status: String, detail: String, phase: String): Unit =
      stageProgressFn(status, detail, phase)
  }

  // ---- Pipeline error types (M3.5) ----

  case class StageError(
    stageName: String,
    equipId: Option[String],
    errorCode: String,
    detail: String
  )

  /** Thrown inside PipelineStages when equipment returns JobFailed.
   *  Caught by recoverWith at the engine level (FabFlowEngine / FabScenarioPipeline). */
  case class StageFailedException(error: StageError)
    extends RuntimeException(s"[${error.stageName}] ${error.errorCode}: ${error.detail}")
    with NoStackTrace

  /** Result of a sub-lot's async processing, used to resolve the AwaitSubLotResult stage.
   *  @param state  the FabDemoState after merge or scrap
   *  @param outcome "merged" if sub-lot merged back, "scrapped" if sub-lot was scrapped */
  case class SubLotResult(state: FabDemoState, outcome: String)
}
