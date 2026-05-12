package net.imadz.fab.service

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout
import net.imadz.fab.events.FabSimulationEvent
import net.imadz.fab.orchestration.FabSimulationEngine
import net.imadz.fab.orchestration.FabSimulationEngine.{SimResult, StartScenario}
import net.imadz.fab.protocol.ActorEquipmentAdapter
import net.imadz.fab.scenario.StandardScenarios

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

@Singleton
class FabDemoService @Inject()(
  classicSystem: akka.actor.ActorSystem
) {
  private implicit val system: ActorSystem[Nothing] =
    akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps(classicSystem).toTyped
  private implicit val ec: ExecutionContext = system.executionContext

  /**
   * Start a demo scenario. Events are published via the given callback
   * (which is connected to the WebSocket hub in FabDemoController).
   */
  def startDemo(scenarioId: String, publisher: FabSimulationEvent => Unit): Future[SimResult] = {
    val scenario = scenarioId match {
      case "photo-cell-5wafer" => StandardScenarios.photoCell5Wafer
      case _ => StandardScenarios.photoCell5Wafer
    }

    val adapter = new ActorEquipmentAdapter()
    val engine = system.systemActorOf(
      FabSimulationEngine(publisher, adapter),
      s"fab-sim-${scenarioId}-${System.currentTimeMillis()}"
    )

    implicit val timeout: Timeout = 10.seconds
    engine.ask[SimResult](ref => StartScenario(scenario, ref))
  }

  def getScenarios: Seq[Map[String, String]] = Seq(
    Map("id" -> "photo-cell-5wafer", "name" -> "Lithography Photo Cell (5 wafers)")
  )

  /** Return the Event Sourcing Ledger (event timeline × aggregate states) for a scenario */
  def getScenarioLedger(scenarioId: String): Map[String, Any] = {
    val (steps, name) = scenarioId match {
      case "photo-cell-5wafer" => (photoCellLedgerA, "Photo Cell 5-Wafer Closed-Loop (Scenario A: All PASS — Happy Path)")
      case _ => (photoCellLedgerA, "Photo Cell 5-Wafer Closed-Loop (Scenario A: All PASS — Happy Path)")
    }
    Map(
      "scenarioId" -> scenarioId,
      "name" -> name,
      "steps" -> steps
    )
  }

  /** Scenario A: All 5 wafers PASS — matching engine phases 0–9 */
  private val photoCellLedgerA: Seq[Map[String, String]] = Seq(
    Map("seq" -> "0", "event" -> "Load FOUP from Stocker",        "lotSource" -> "—",         "lotRework" -> "—",    "wafer" -> "—",         "saga" -> "—",  "phase" -> "Load"),
    Map("seq" -> "1", "event" -> "Transport: STOCKER → LITHO",    "lotSource" -> "—",         "lotRework" -> "—",    "wafer" -> "—",         "saga" -> "—",  "phase" -> "Transport"),
    Map("seq" -> "2", "event" -> "FOUP arrives at Litho",         "lotSource" -> "—",         "lotRework" -> "—",    "wafer" -> "—",         "saga" -> "—",  "phase" -> "AtEqp"),
    Map("seq" -> "3", "event" -> "Litho: ProcessRecipe (8s)",     "lotSource" -> "—",         "lotRework" -> "—",    "wafer" -> "(process)", "saga" -> "—",  "phase" -> "Process"),
    Map("seq" -> "4", "event" -> "Transport: LITHO → CD-SEM",     "lotSource" -> "—",         "lotRework" -> "—",    "wafer" -> "—",         "saga" -> "—",  "phase" -> "Transport"),
    Map("seq" -> "5", "event" -> "FOUP arrives at CD-SEM",        "lotSource" -> "—",         "lotRework" -> "—",    "wafer" -> "—",         "saga" -> "—",  "phase" -> "AtEqp"),
    Map("seq" -> "6", "event" -> "CD-SEM: Measure CD (5 wafers)", "lotSource" -> "—",         "lotRework" -> "—",    "wafer" -> "(measure)", "saga" -> "—",  "phase" -> "Measure"),
    Map("seq" -> "7", "event" -> "Classify: 5/5 PASS",            "lotSource" -> "—",         "lotRework" -> "—",    "wafer" -> "PASS×5",    "saga" -> "—",  "phase" -> "Decide"),
    Map("seq" -> "8", "event" -> "Return FOUP to Stocker",        "lotSource" -> "—",         "lotRework" -> "—",    "wafer" -> "—",         "saga" -> "—",  "phase" -> "Return"),
    Map("seq" -> "9", "event" -> "Demo Completed",                "lotSource" -> "—",         "lotRework" -> "—",    "wafer" -> "PASS×5",    "saga" -> "—",  "phase" -> "Complete"),
  )
}
