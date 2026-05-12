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
}
