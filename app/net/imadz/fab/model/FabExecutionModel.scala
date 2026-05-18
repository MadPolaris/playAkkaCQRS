package net.imadz.fab.model

import akka.actor.typed.ActorRef
import net.imadz.application.aggregates.LotProtocol.{LotCommand, LotConfirmation}
import net.imadz.application.services.transactor.FabSagaProtocol.FabSagaConfirmation
import net.imadz.common.CborSerializable
import net.imadz.common.CommonTypes.Id
import net.imadz.fab.events.FabSimulationEvent
import net.imadz.fab.protocol.ActorEquipmentAdapter
import net.imadz.fab.routing.OcapRuleDefinition
import net.imadz.fab.scenario.FabSimulationScenario

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NoStackTrace

/**
 * Shared execution model types used by FabDemoPipeline, FabScenarioPipeline,
 * and FabFlowEngine (the three PorExecutor pipeline functions).
 *
 * Extracted from [[net.imadz.fab.chain.FabDemoPipeline]] to remove circular-ish
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
    equipmentState: Map[String, EquipmentState] = Map.empty
  ) extends CborSerializable

  case class FabDemoContext(
    scenario: FabSimulationScenario,
    foupId: String,
    lotRef: akka.cluster.sharding.typed.scaladsl.EntityRef[LotCommand],
    reworkLotRef: akka.cluster.sharding.typed.scaladsl.EntityRef[LotCommand],
    waferUUIDs: Map[String, Id],
    sourceLotId: Id,
    reworkLotId: Id,
    adapter: ActorEquipmentAdapter,
    publisher: FabSimulationEvent => Unit,
    ignoreLotReply: ActorRef[LotConfirmation],
    sagaTx: (Id, Id, Set[Id], Set[String], Option[Id]) => Future[FabSagaConfirmation],
    speedMultiplier: Double,
    scrapLotRef: Option[akka.cluster.sharding.typed.scaladsl.EntityRef[LotCommand]] = None,
    scrapLotId: Option[Id] = None,
    childLotRefs: Map[String, akka.cluster.sharding.typed.scaladsl.EntityRef[LotCommand]] = Map.empty,
    childLotIds: Map[String, Id] = Map.empty,
    ocapRules: List[OcapRuleDefinition] = Nil
  )(implicit val ec: ExecutionContext)

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
}
