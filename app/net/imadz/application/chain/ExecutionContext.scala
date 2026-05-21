package net.imadz.application.chain

import akka.actor.typed.ActorRef
import net.imadz.application.aggregates.LotProtocol.{LotCommand, LotConfirmation}
import net.imadz.application.services.transactor.FabSagaProtocol.FabSagaConfirmation
import net.imadz.common.CommonTypes.Id
import net.imadz.domain.routing.OcapRuleDefinition
import net.imadz.application.chain.FabExecutionModel.SubLotResult

import scala.collection.concurrent.TrieMap
import scala.concurrent.{Future, Promise}

/**
 * Pure kernel execution context — carries aggregate references and saga callback.
 *
 * Zero knowledge of:
 *   - WebSocket / UI publishing
 *   - Equipment simulation adapters
 *   - Scenario configuration
 *   - Fault injection
 *
 * Those concerns belong to the Demo layer or infrastructure Projections.
 */
trait ExecutionContext {

  def foupId: String
  def sourceLotId: Id

  def waferUUIDs: Map[String, Id]

  /** Primary lot aggregate reference */
  def lotRef: akka.cluster.sharding.typed.scaladsl.EntityRef[LotCommand]

  /** Child lot refs keyed by suffix ("rwk", "pilot", "sample", "hold", "scrap") */
  def childLotRefs: Map[String, akka.cluster.sharding.typed.scaladsl.EntityRef[LotCommand]]

  /** Child lot IDs keyed by suffix */
  def childLotIds: Map[String, Id]

  /** Scrap lot (Saga TCC target for wafer scrap) */
  def scrapLotRef: Option[akka.cluster.sharding.typed.scaladsl.EntityRef[LotCommand]]
  def scrapLotId: Option[Id]

  /** Saga TCC transaction callback */
  def sagaTx: (Id, Id, Set[Id], Set[String], Option[Id]) => Future[FabSagaConfirmation]

  /** OCAP rules active for this execution */
  def ocapRules: List[OcapRuleDefinition]

  /** Dead letter receiver for fire-and-forget Lot commands */
  def ignoreLotReply: ActorRef[LotConfirmation]

  /** Runtime-only Promise storage for AwaitSubLotResult suspension.
   *  Keyed by lotKey ("rwk", "pilot", etc.). NOT serialized — re-populated on recovery. */
  def awaitPromises: TrieMap[String, Promise[SubLotResult]]

  /** Intra-stage progress notification.
   *  In the Actor path, this routes through the journal; in non-Actor paths,
   *  it falls back to direct UI publishing. Replaces the deprecated
   *  [[FabDemoContext.publisher(GlobalStatusChanged(...))]] bypass. */
  def stageProgress(status: String, detail: String, phase: String): Unit

  /** ExecutionContext implicitly provides thread pool */
  implicit def ec: scala.concurrent.ExecutionContext
}
