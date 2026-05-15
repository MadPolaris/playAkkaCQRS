package controllers

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.stream.Materializer
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, MergeHub, Sink, Source}
import net.imadz.fab.events.{DomainEventRecorded, FabSimulationEvent}
import net.imadz.fab.projection.FabDemoEventBridge
import net.imadz.fab.service.FabDemoService
import play.api.i18n.{I18nSupport, Lang}
import play.api.libs.json.Json
import play.api.mvc.{BaseController, ControllerComponents, WebSocket}

import javax.inject.Inject
import scala.concurrent.ExecutionContext

class FabDemoController @Inject()(
  val controllerComponents: ControllerComponents,
  implicit val classicSystem: akka.actor.ActorSystem,
  implicit val mat: Materializer,
  implicit val ec: ExecutionContext,
  fabDemoService: FabDemoService
) extends BaseController with I18nSupport {

  private implicit val typedSystem: ActorSystem[Nothing] = classicSystem.toTyped

  // WebSocket event hub — pure pass-through, no aggregation.
  // Aggregate state is computed by the pipeline (buildAggregateState)
  // and emitted as AggregateStateUpdated events at every stage transition.
  private val (hubSink, hubSource): (Sink[FabSimulationEvent, _], Source[FabSimulationEvent, _]) =
    MergeHub.source[FabSimulationEvent]
      .toMat(BroadcastHub.sink[FabSimulationEvent](bufferSize = 2048))(Keep.both)
      .run()

  /** Publish a simulation event to all connected WebSocket clients */
  private def publishEvent(event: FabSimulationEvent): Unit = {
    akka.stream.scaladsl.Source.single(event).runWith(hubSink)
  }

  // Register system-wide publisher so recovery replays have a WebSocket publisher
  fabDemoService.setSystemWidePublisher(publishEvent)

  // Bridge: subscribes EventStream for domain events from FabProcessProjection,
  // maps them to FabSimulationEvents, and pushes to the WebSocket hub
  private val eventBridge = typedSystem.systemActorOf(
    FabDemoEventBridge(publishEvent),
    "fab-demo-event-bridge"
  )

  /** Render the Fab demo page */
  def index() = Action { implicit request =>
    val langParam = request.getQueryString("lang").getOrElse("")
    val langs: Seq[Lang] = if (langParam.nonEmpty) Seq(Lang(langParam)) else request.acceptLanguages
    val messages = messagesApi.preferred(langs)
    Ok(views.html.fabDemo()(messages))
  }

  /** WebSocket endpoint for real-time simulation events */
  def socket: WebSocket = WebSocket.accept[String, String] { _ =>
    Flow.fromSinkAndSource(Sink.ignore, hubSource.map { event =>
      val typeName = event.getClass.getSimpleName.replace("$", "")
      val data = writeEventData(event)
      Json.obj("type" -> typeName, "data" -> data).toString()
    })
  }

  private def writeEventData(event: FabSimulationEvent): play.api.libs.json.JsValue = {
    import net.imadz.fab.events._
    event match {
      case DemoStarted(sid, name, size, wids) => Json.obj("scenarioId" -> sid, "name" -> name, "lotSize" -> size, "waferIds" -> wids)
      case DemoCompleted(lid, tw, pw, rw, sw) => Json.obj("lotId" -> lid, "totalWafers" -> tw, "passedWafers" -> pw, "reworkedWafers" -> rw, "scrappedWafers" -> sw)
      case EquipmentStateChanged(eid, aid, st, job) => Json.obj("equipmentId" -> eid, "areaId" -> aid, "status" -> st, "currentJob" -> job)
      case FoupInTransit(fid, from, to, eta) => Json.obj("foupId" -> fid, "fromArea" -> from, "toArea" -> to, "etaMs" -> eta)
      case FoupArrivedAtPort(fid, eid, pid) => Json.obj("foupId" -> fid, "equipmentId" -> eid, "portId" -> pid)
      case ProcessingStarted(eid, recipe, ms) => Json.obj("equipmentId" -> eid, "recipeId" -> recipe, "estimatedMs" -> ms)
      case ProcessingCompleted(eid, jid, ok, detail) => Json.obj("equipmentId" -> eid, "jobId" -> jid, "success" -> ok, "detail" -> detail)
      case MeasurementResultEvent(wid, cd, cls, spec) => Json.obj("waferId" -> wid, "cdNm" -> cd, "classification" -> cls, "specLimit" -> spec)
      case DecisionMade(wid, act, detail) => Json.obj("waferId" -> wid, "action" -> act, "detail" -> detail)
      case SagaOperationEvent(tid, op, st, src, tgt, wids) => Json.obj("transactionId" -> tid, "operation" -> op, "status" -> st, "sourceLotId" -> src, "targetLotId" -> tgt, "relatedWaferIds" -> wids)
      case LotUpdated(lid, act, scr, steps, pass, rw) => Json.obj("lotId" -> lid, "activeWafers" -> act, "scrappedWafers" -> scr, "completedSteps" -> steps, "passedWafers" -> pass, "reworkedWafers" -> rw)
      case OrchestratorCommand(cid, eid, ct, desc, wids) => Json.obj("commandId" -> cid, "targetEquipmentId" -> eid, "commandType" -> ct, "description" -> desc, "relatedWaferIds" -> wids)
      case FoupStateChanged(fid, st, awc, rwc, loc, lotId, rwkLotId) => Json.obj("foupId" -> fid, "status" -> st, "activeWaferCount" -> awc, "reworkWaferCount" -> rwc, "location" -> loc, "lotId" -> lotId, "reworkLotId" -> rwkLotId)
      case LedgerStepAdvanced(seq, name) => Json.obj("stepSeq" -> seq, "stepName" -> name)
      case DomainEventRecorded(evtType, aggType, aggId, data, ts, layer) => Json.obj(
        "eventType" -> evtType, "aggregateType" -> aggType, "aggregateId" -> aggId,
        "data" -> data, "timestamp" -> ts, "layer" -> layer)
      case GlobalStatusChanged(st, detail, phase) => Json.obj("status" -> st, "detail" -> detail, "phase" -> phase)
      case ScrapEvent(wid, reason) => Json.obj("waferId" -> wid, "reason" -> reason)
      case AggregateStateUpdated(srcLot, childLots, wafers) => Json.obj(
        "sourceLot" -> Json.obj("lotId" -> srcLot.lotId, "status" -> srcLot.status, "waferCount" -> srcLot.waferCount, "passCount" -> srcLot.passCount, "scrapCount" -> srcLot.scrapCount, "currentArea" -> srcLot.currentArea),
        "childLots" -> childLots.map(cl => Json.obj("lotId" -> cl.lotId, "status" -> cl.status, "waferCount" -> cl.waferCount, "passCount" -> cl.passCount, "scrapCount" -> cl.scrapCount, "currentArea" -> cl.currentArea)),
        "wafers" -> wafers.map(w => Json.obj("waferId" -> w.waferId, "status" -> w.status, "lotId" -> w.lotId, "classification" -> w.classification, "reworkCount" -> w.reworkCount))
      )
    }
  }

  /** Start a demo scenario */
  def startDemo(scenarioId: String) = Action.async {
    fabDemoService.startDemo(scenarioId, publishEvent).map { result =>
      Ok(Json.obj("success" -> true, "message" -> s"WorkOrder ${result.workOrderId} ${result.phase}", "workOrderId" -> result.workOrderId))
    }
  }

  /** Get available scenarios */
  def getScenarios = Action {
    Ok(Json.toJson(fabDemoService.getScenarios))
  }

  /** Start a dynamic routing demo by product ID */
  def startProductDemo(productId: String) = Action.async {
    fabDemoService.startDemoWithProduct(productId, publishEvent).map { result =>
      Ok(Json.obj("success" -> true, "message" -> s"WorkOrder ${result.workOrderId} ${result.phase}", "workOrderId" -> result.workOrderId))
    }
  }

  /** Get scenario event-sourcing ledger (time-line of expected events per aggregate) */
  def getScenarioLedger(scenarioId: String) = Action {
    val ledger = fabDemoService.getScenarioLedger(scenarioId)
    val steps = ledger("steps").asInstanceOf[Seq[Map[String, String]]].map { step =>
      Json.obj(
        "seq" -> step("seq"),
        "event" -> step("event"),
        "lotSource" -> step("lotSource"),
        "lotRework" -> step("lotRework"),
        "wafer" -> step("wafer"),
        "saga" -> step("saga"),
        "phase" -> step("phase")
      )
    }
    Ok(Json.obj(
      "scenarioId" -> ledger("scenarioId").asInstanceOf[String],
      "name" -> ledger("name").asInstanceOf[String],
      "lotReworkLabel" -> ledger("lotReworkLabel").asInstanceOf[String],
      "steps" -> steps
    ))
  }

  /** Query real aggregate entity state by work order ID */
  def getEntityState(workOrderId: String) = Action.async {
    import scala.concurrent.Future
    fabDemoService.queryEntityState(workOrderId).map { state =>
      val l = state.lot
      val lotPhase: String = l.phase.map(_.toString).getOrElse("")
      val lotProduct: String = l.productId.getOrElse("")
      val lotIdStr: String = l.lotId.map(_.toString).getOrElse("")
      val lotFoup: String = l.loadedFoupId.getOrElse("")

      def lotJson(conf: net.imadz.application.aggregates.LotProtocol.LotConfirmation): play.api.libs.json.JsValue = {
        val p: String = conf.phase.map(_.toString).getOrElse("")
        val lid: String = conf.lotId.map(_.toString).getOrElse("")
        val foup: String = conf.loadedFoupId.getOrElse("")
        Json.obj(
          "phase" -> p,
          "lotId" -> lid,
          "waferIds" -> conf.waferIds.map(_.toString),
          "waferCount" -> conf.waferIds.size,
          "reservedWafers" -> conf.reservedWafers.map { case (tid, wids) =>
            Json.obj("transferId" -> tid.toString, "waferIds" -> wids.map(_.toString))
          },
          "incomingWafers" -> conf.incomingWafers.map { case (tid, wids) =>
            Json.obj("transferId" -> tid.toString, "waferIds" -> wids.map(_.toString))
          },
          "completedTransferIds" -> conf.completedTransferIds.map(_.toString),
          "areaVisitHistory" -> conf.areaVisitHistory,
          "loadedFoupId" -> foup,
          "waferClassifications" -> conf.waferClassifications.map { case (id, cls) => id.toString -> cls },
          "completedJobs" -> conf.completedJobs.toSeq,
          "measuredWafers" -> conf.measuredWafers.map(_.toString).toSeq
        )
      }

      Ok(Json.obj(
        "workOrderId" -> state.workOrderId,
        "sourceLot" -> Json.obj(
          "phase" -> lotPhase,
          "productId" -> lotProduct,
          "lotId" -> lotIdStr,
          "waferIds" -> l.waferIds.map(_.toString),
          "waferCount" -> l.waferIds.size,
          "reservedWafers" -> l.reservedWafers.map { case (tid, wids) =>
            Json.obj("transferId" -> tid.toString, "waferIds" -> wids.map(_.toString))
          },
          "incomingWafers" -> l.incomingWafers.map { case (tid, wids) =>
            Json.obj("transferId" -> tid.toString, "waferIds" -> wids.map(_.toString))
          },
          "completedTransferIds" -> l.completedTransferIds.map(_.toString),
          "areaVisitHistory" -> l.areaVisitHistory,
          "routingStepReentry" -> l.routingStepReentry,
          "loadedFoupId" -> lotFoup,
          "waferClassifications" -> l.waferClassifications.map { case (id, cls) => id.toString -> cls },
          "completedJobs" -> l.completedJobs.toSeq,
          "measuredWafers" -> l.measuredWafers.map(_.toString).toSeq,
          "currentStepIndex" -> l.currentStepIndex
        ),
        "childLots" -> state.childLots.map { case (key, conf) => key -> lotJson(conf) },
        "wafers" -> l.waferIds.map { wid =>
          val widStr = wid.toString
          val classification = l.waferClassifications.getOrElse(wid, "Pending")
          Json.obj(
            "waferId" -> widStr,
            "status" -> (if (classification == "SCRAP") "Scrapped" else "Active"),
            "lotId" -> lotIdStr,
            "classification" -> classification
          )
        }
      ))
    }.recover { case ex =>
      Ok(Json.obj("error" -> ex.getMessage, "workOrderId" -> workOrderId))
    }
  }
}
