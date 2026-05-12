package net.imadz.fab.service

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.AskPattern._
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.util.Timeout
import net.imadz.application.aggregates.LotAggregate.LotEntityTypeKey
import net.imadz.application.aggregates.WaferAggregate.WaferEntityTypeKey
import net.imadz.application.aggregates.LotProtocol._
import net.imadz.application.aggregates.WaferProtocol._
import net.imadz.application.aggregates.process.FabProcessAggregate
import net.imadz.application.aggregates.process.FabProcessProtocol._
import net.imadz.application.services.FabSagaService
import net.imadz.application.services.transactor.FabSagaProtocol.FabSagaConfirmation
import net.imadz.common.CommonTypes.Id
import net.imadz.fab.events.FabSimulationEvent
import net.imadz.fab.orchestration.FabSimulationCoordinator
import net.imadz.fab.orchestration.FabSimulationCoordinator.{SimResult, StartScenario}
import net.imadz.fab.protocol.ActorEquipmentAdapter
import net.imadz.fab.scenario.StandardScenarios

import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

@Singleton
class FabDemoService @Inject()(
  classicSystem: akka.actor.ActorSystem,
  sharding: ClusterSharding,
  fabSagaService: FabSagaService
) {
  private implicit val system: ActorSystem[Nothing] =
    akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps(classicSystem).toTyped
  private implicit val ec: ExecutionContext = system.executionContext
  private implicit val timeout: Timeout = 10.seconds

  /**
   * Start a demo scenario. Now creates real EventSourced Lot + Wafer aggregates
   * and wires the FabSagaService for TCC split/merge transactions.
   */
  def startDemo(scenarioId: String, publisher: FabSimulationEvent => Unit): Future[SimResult] = {
    val scenario = scenarioId match {
      case "photo-cell-5wafer" => StandardScenarios.photoCell5Wafer
      case _ => StandardScenarios.photoCell5Wafer
    }

    // Generate unique UUIDs for this run (deterministic from run key avoids cross-run collision)
    val runKey = UUID.randomUUID().toString.take(8)
    val waferUUIDs: Map[String, Id] = scenario.waferIds.map { wid =>
      wid -> UUID.nameUUIDFromBytes(s"$runKey-$wid".getBytes)
    }.toMap
    val sourceLotId: Id = UUID.nameUUIDFromBytes(s"$runKey-source-lot".getBytes)
    val reworkLotId: Id = UUID.nameUUIDFromBytes(s"$runKey-rework-lot".getBytes)

    val lotRef = sharding.entityRefFor(LotEntityTypeKey, sourceLotId.toString)
    val reworkLotRef = sharding.entityRefFor(LotEntityTypeKey, reworkLotId.toString)
    val waferRefs: Map[String, akka.cluster.sharding.typed.scaladsl.EntityRef[WaferCommand]] =
      waferUUIDs.map { case (wid, uuid) => wid -> sharding.entityRefFor(WaferEntityTypeKey, uuid.toString) }

    // Saga split callback — calls real FabSagaService TCC transaction
    val sagaSplitFn: (Id, Id, Set[Id]) => Future[FabSagaConfirmation] =
      (srcId, tgtId, wids) => fabSagaService.splitLot(srcId, tgtId, wids)

    // Create source Lot + Rework Lot + 5 Wafers before starting the coordinator
    val createEntities: Future[Unit] = for {
      // Create source lot
      _ <- lotRef.ask[LotConfirmation](ref =>
        CreateLot(s"PHOTO-CELL-$runKey", waferUUIDs.values.toSet, ref)
      )
      // Create rework lot (empty, will receive wafers via Saga split)
      _ <- reworkLotRef.ask[LotConfirmation](ref =>
        CreateLot(s"PHOTO-CELL-REWORK-$runKey", Set.empty, ref)
      )
      // Create 5 wafers
      _ <- Future.sequence(waferUUIDs.map { case (_, uuid) =>
        val waferRef = sharding.entityRefFor(WaferEntityTypeKey, uuid.toString)
        waferRef.ask[WaferConfirmation](ref => CreateWafer(sourceLotId, ref))
      })
    } yield ()

    // After entities are created, start the coordinator
    createEntities.flatMap { _ =>
      val processId = UUID.randomUUID().toString
      val processRef = sharding.entityRefFor(FabProcessAggregate.ProcessEntityTypeKey, processId)

      val adapter = new ActorEquipmentAdapter()
      val coordinator = system.systemActorOf(
        FabSimulationCoordinator(
          publisher, adapter, processRef,
          lotRef, reworkLotRef, waferRefs, waferUUIDs,
          sagaSplitFn, sourceLotId, reworkLotId
        ),
        s"fab-sim-${scenarioId}-${System.currentTimeMillis()}"
      )

      coordinator.ask[SimResult](ref => StartScenario(scenario, ref))
    }
  }

  def getScenarios: Seq[Map[String, String]] = Seq(
    Map("id" -> "photo-cell-5wafer", "name" -> "Lithography Photo Cell (5 wafers)")
  )

  /** Return the Event Sourcing Ledger (event timeline × aggregate states) for a scenario */
  def getScenarioLedger(scenarioId: String): Map[String, Any] = {
    val (steps, name) = scenarioId match {
      case "photo-cell-5wafer" => (photoCellLedger, "Photo Cell 5-Wafer — 2 PASS + 2 Rework → PASS + 1 SCRAP")
      case _ => (photoCellLedger, "Photo Cell 5-Wafer — 2 PASS + 2 Rework → PASS + 1 SCRAP")
    }
    Map(
      "scenarioId" -> scenarioId,
      "name" -> name,
      "steps" -> steps
    )
  }

  /** Mixed scenario: W1=PASS, W2=PASS, W3=FAIL→Rework→PASS, W4=FAIL→Rework→PASS, W5=SCRAP */
  private val photoCellLedger: Seq[Map[String, String]] = Seq(
    Map("seq" -> "0",  "event" -> "Load FOUP from Stocker (5 wafers)",      "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "—",         "saga" -> "—",      "phase" -> "Load"),
    Map("seq" -> "1",  "event" -> "Transport: STOCKER → LITHO",              "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "—",         "saga" -> "—",      "phase" -> "Transport"),
    Map("seq" -> "2",  "event" -> "FOUP arrives at Litho",                   "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "—",         "saga" -> "—",      "phase" -> "AtEqp"),
    Map("seq" -> "3",  "event" -> "Litho: ProcessRecipe LITHO-28-001",       "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "(process)", "saga" -> "—",      "phase" -> "Process"),
    Map("seq" -> "4",  "event" -> "Transport: LITHO → CD-SEM",               "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "—",         "saga" -> "—",      "phase" -> "Transport"),
    Map("seq" -> "5",  "event" -> "FOUP arrives at CD-SEM",                  "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "—",         "saga" -> "—",      "phase" -> "AtEqp"),
    Map("seq" -> "6",  "event" -> "CD-SEM: Measure CD (5 wafers)",           "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "(measure)", "saga" -> "—",      "phase" -> "Measure"),
    Map("seq" -> "7",  "event" -> "Classify: W1=PASS W2=PASS W3=FAIL W4=FAIL W5=SCRAP", "lotSource" -> "—", "lotRework" -> "—", "wafer" -> "2PASS 2FAIL 1SCRAP", "saga" -> "—", "phase" -> "Decide"),
    Map("seq" -> "8",  "event" -> "Split: W3,W4 → Rework Lot",       "lotSource" -> "Active(3w)", "lotRework" -> "Active(2w)", "wafer" -> "W3,W4→rework", "saga" -> "Initiated", "phase" -> "Split"),
    Map("seq" -> "9",  "event" -> "Transport Rework: CDSEM → LITHO",          "lotSource" -> "(read)",   "lotRework" -> "(read)",   "wafer" -> "(read)",    "saga" -> "Committed", "phase" -> "Rework"),
    Map("seq" -> "10", "event" -> "FOUP arrives at Litho (Rework pass #1)",   "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "—",         "saga" -> "—",      "phase" -> "AtEqp"),
    Map("seq" -> "11", "event" -> "Rework Litho: REWORK-LITHO-001",           "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "(rework)",  "saga" -> "—",      "phase" -> "Process"),
    Map("seq" -> "12", "event" -> "Transport: LITHO → CD-SEM (rework FOUP)",  "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "—",         "saga" -> "—",      "phase" -> "Transport"),
    Map("seq" -> "13", "event" -> "FOUP arrives at CD-SEM",                   "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "—",         "saga" -> "—",      "phase" -> "AtEqp"),
    Map("seq" -> "14", "event" -> "CD-SEM: Measure reworked wafers (W3,W4)",  "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "(measure)", "saga" -> "—",      "phase" -> "Measure"),
    Map("seq" -> "15", "event" -> "Classify: W3=PASS W4=PASS (rework ✓)",     "lotSource" -> "—",        "lotRework" -> "—",     "wafer" -> "PASS×2",    "saga" -> "—",      "phase" -> "Decide"),
    Map("seq" -> "16", "event" -> "Merge: W3,W4 → Source Lot",        "lotSource" -> "Active(5w)", "lotRework" -> "Empty", "wafer" -> "W3,W4→source","saga" -> "Completed","phase" -> "Return"),
    Map("seq" -> "17", "event" -> "Return FOUP to Stocker + Demo Completed",  "lotSource" -> "Sealed",   "lotRework" -> "—",     "wafer" -> "4PASS 1SCRAP","saga" -> "—",     "phase" -> "Complete"),
  )
}
