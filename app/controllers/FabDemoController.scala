package controllers

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.stream.Materializer
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, MergeHub, Sink, Source}
import net.imadz.fab.events.FabSimulationEvent
import net.imadz.fab.service.FabDemoService
import play.api.libs.json.{Json, Writes}
import play.api.mvc.{BaseController, ControllerComponents, WebSocket}

import javax.inject.Inject
import scala.concurrent.ExecutionContext

class FabDemoController @Inject()(
  val controllerComponents: ControllerComponents,
  implicit val classicSystem: akka.actor.ActorSystem,
  implicit val mat: Materializer,
  implicit val ec: ExecutionContext,
  fabDemoService: FabDemoService
) extends BaseController {

  private implicit val typedSystem: ActorSystem[Nothing] = classicSystem.toTyped

  // WebSocket event hub (same pattern as ShowcaseController)
  private val (hubSink, hubSource): (Sink[FabSimulationEvent, _], Source[FabSimulationEvent, _]) =
    MergeHub.source[FabSimulationEvent]
      .toMat(BroadcastHub.sink[FabSimulationEvent])(Keep.both)
      .run()

  /** Render the Fab demo page */
  def index() = Action {
    Ok(views.html.fabDemo())
  }

  /** WebSocket endpoint for real-time simulation events */
  def socket: WebSocket = WebSocket.accept[String, String] { _ =>
    implicit val eventWrites: Writes[FabSimulationEvent] = Writes { event =>
      Json.obj(
        "type" -> event.getClass.getSimpleName.replace("$", ""),
        "data" -> event.toString
      )
    }
    Flow.fromSinkAndSource(Sink.ignore, hubSource.map(e =>
      Json.toJson(e)(eventWrites).toString()
    ))
  }

  /** Start a demo scenario */
  def startDemo(scenarioId: String) = Action.async {
    fabDemoService.startDemo(scenarioId).map { result =>
      Ok(Json.obj("success" -> result.success, "message" -> result.message))
    }
  }

  /** Get available scenarios */
  def getScenarios = Action {
    Ok(Json.toJson(fabDemoService.getScenarios))
  }
}
