package controllers

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.stream.Materializer
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.persistence.query.{PersistenceQuery, Sequence}
import akka.persistence.query.scaladsl.{CurrentEventsByPersistenceIdQuery, ReadJournal}
import akka.stream.Materializer
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, MergeHub, Sink, Source}
import akka.contrib.persistence.mongodb.MongoReadJournal
import net.imadz.domain.events.{DomainEventRecorded, FabSimulationEvent, RecoveryEvent, FaultInjected, DynamicStageInjected, PipelineTimelineSnapshot}
import net.imadz.application.services.FabDemoService
import net.imadz.application.chain.FabPipelineExecutionActor
import akka.projection.ProjectionBehavior
import net.imadz.application.projection.{FabDemoEventBridge, FabDemoViewProjection, FabPipelineProjection}
import net.imadz.application.routing.{RouteCardCompiler, RouteCompiler}
import net.imadz.domain.routing._
import net.imadz.infrastructure.repositories.routing.{OcapRuleStore, RouteDefinitionStore}
import net.imadz.infrastructure.persistence.LotEventAdapter
import net.imadz.domain.entities.LotEntity.LotEvent
import java.util.UUID
import play.api.i18n.{I18nSupport, Lang}
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{BaseController, ControllerComponents, WebSocket}

import javax.inject.Inject
import scala.concurrent.ExecutionContext

class FabDemoController @Inject()(
  val controllerComponents: ControllerComponents,
  implicit val classicSystem: akka.actor.ActorSystem,
  implicit val mat: Materializer,
  implicit val ec: ExecutionContext,
  fabDemoService: FabDemoService,
  ocapRuleStore: OcapRuleStore
) extends BaseController with I18nSupport {

  private implicit val typedSystem: ActorSystem[Nothing] = classicSystem.toTyped

  // WebSocket event hub — pure pass-through, no aggregation.
  // Aggregate state is computed by FabDemoViewProjection (CQRS read-side)
  // and emitted as AggregateStateUpdated events driven by real journal events.
  private val (hubSink, hubSource): (Sink[FabSimulationEvent, _], Source[FabSimulationEvent, _]) =
    MergeHub.source[FabSimulationEvent]
      .toMat(BroadcastHub.sink[FabSimulationEvent](bufferSize = 2048))(Keep.both)
      .run()

  /** Publish a simulation event to all connected WebSocket clients */
  private def publishEvent(event: FabSimulationEvent): Unit = {
    akka.stream.scaladsl.Source.single(event).runWith(hubSink)
  }

  /** P4: process-wide monotonic frame counter — lets the client detect and drop
    * semantically-stale snapshots (projection lag) instead of animating them. */
  private val frameSeq = new java.util.concurrent.atomic.AtomicLong(0)
  private def frameJson(event: FabSimulationEvent): String = {
    val typeName = event.getClass.getSimpleName.replace("$", "")
    Json.obj("seq" -> frameSeq.incrementAndGet(), "type" -> typeName, "data" -> writeEventData(event)).toString()
  }

  // Register system-wide publisher so recovery replays have a WebSocket publisher
  fabDemoService.setSystemWidePublisher(publishEvent)

  // Bridge: subscribes EventStream for domain events from FabProcessProjection,
  // maps them to FabSimulationEvents, and pushes to the WebSocket hub
  private val eventBridge = typedSystem.systemActorOf(
    FabDemoEventBridge(publishEvent),
    "fab-demo-event-bridge"
  )

  // CQRS view projection: subscribes "fab-view" tagged journal events,
  // reconstructs AggregateStateUpdated from real events, pushes to WebSocket hub
  typedSystem.systemActorOf(
    ProjectionBehavior(FabDemoViewProjection.createProjection(typedSystem, publishEvent)),
    "fab-demo-view-projection"
  )

  // Pipeline timeline projection: subscribes "fab-pipeline" tagged journal events,
  // converts StageCompleted → PipelineTimelineSnapshot for UI progress bar
  typedSystem.systemActorOf(
    ProjectionBehavior(FabPipelineProjection.createProjection(typedSystem, publishEvent)),
    "fab-pipeline-projection"
  )

  /** Render the Fab M3.5 3D digital-twin page (Monarch-driven, WebGL) */
  def m35ThreeD() = Action { implicit request =>
    Ok(views.html.fabM35ThreeD())
  }

    /** Render the Fab simulation page */
  def index() = Action { implicit request =>
    val langParam = request.getQueryString("lang").getOrElse("")
    val langs: Seq[Lang] = if (langParam.nonEmpty) Seq(Lang(langParam)) else request.acceptLanguages
    val messages = messagesApi.preferred(langs)
    Ok(views.html.fabSimulation()(messages))
  }

  /** Render the Route Designer page */
  def designer() = Action { implicit request =>
    val langParam = request.getQueryString("lang").getOrElse("")
    val langs: Seq[Lang] = if (langParam.nonEmpty) Seq(Lang(langParam)) else request.acceptLanguages
    val messages = messagesApi.preferred(langs)
    Ok(views.html.fabDesigner()(messages))
  }

  /** WebSocket endpoint for real-time simulation events.
   * Frontend applies its own bufferTime(100ms) batching via RxJS. */
  def socket: WebSocket = WebSocket.accept[String, String] { _ =>
    Flow.fromSinkAndSource(Sink.ignore, hubSource.map(frameJson))
  }

  private def writeEventData(event: FabSimulationEvent): play.api.libs.json.JsValue = {
    import net.imadz.domain.events._
    event match {
      case DemoStarted(sid, name, size, wids) => Json.obj("scenarioId" -> sid, "name" -> name, "lotSize" -> size, "waferIds" -> wids)
      case RecoveryCompleted(lid, tw, pw, rw, sw) => Json.obj("lotId" -> lid, "totalWafers" -> tw, "passedWafers" -> pw, "reworkedWafers" -> rw, "scrappedWafers" -> sw)
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
      case LedgerStepAdvanced(seq, name, nodeId, subProcess, branchDecision) => Json.obj("stepSeq" -> seq, "stepName" -> name, "currentNodeId" -> nodeId, "activeSubProcess" -> subProcess, "branchDecision" -> branchDecision)
      case DomainEventRecorded(evtType, aggType, aggId, data, ts, layer) => Json.obj(
        "eventType" -> evtType, "aggregateType" -> aggType, "aggregateId" -> aggId,
        "data" -> data, "timestamp" -> ts, "layer" -> layer)
      case GlobalStatusChanged(st, detail, phase) => Json.obj("status" -> st, "detail" -> detail, "phase" -> phase)
      case AreaStateChanged(aid, name, st, eid, job, detail, qd) =>
        Json.obj("areaId" -> aid, "displayName" -> name, "status" -> st, "equipmentId" -> eid, "job" -> job, "detail" -> detail, "queueDepth" -> qd)
      case ScrapEvent(wid, reason) => Json.obj("waferId" -> wid, "reason" -> reason)
      case OcapActionTriggered(rid, rname, atype, detail, wafers) => Json.obj("ruleId" -> rid, "ruleName" -> rname, "actionType" -> atype, "detail" -> detail, "affectedWafers" -> wafers)
      case PipelineStageFailed(stageName, equipId, errorCode, detail, ts) => Json.obj("stageName" -> stageName, "equipId" -> equipId, "errorCode" -> errorCode, "detail" -> detail, "timestamp" -> ts)
      // M3.5 Self-Healing events
      case RecoveryEvent(woId, rt, er, ps, rms, det) => Json.obj("workOrderId" -> woId, "recoveryType" -> rt, "eventsReplayed" -> er, "phasesSkipped" -> ps, "recoveryTimeMs" -> rms, "detail" -> det)
      case FaultInjected(woId, eqId, ft, pn, res, resolv) => Json.obj("workOrderId" -> woId, "equipmentId" -> eqId, "faultType" -> ft, "phaseName" -> pn, "resolved" -> res, "resolution" -> resolv)
      case DynamicStageInjected(woId, pnid, ist, tbr, si) => Json.obj("workOrderId" -> woId, "parentNodeId" -> pnid, "injectedStageType" -> ist, "triggeredByRule" -> tbr, "stageIndex" -> si)
      case PipelineTimelineSnapshot(woId, tp, cp, cur, cpi, fp, rp, ot) => Json.obj("workOrderId" -> woId, "totalPhases" -> tp, "completedPhases" -> cp, "currentPhase" -> cur, "currentPhaseIndex" -> cpi, "failedPhases" -> fp, "recoveredPhases" -> rp, "ocapTriggers" -> ot)
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

  /** Get the route graph visualization data for a scenario.
   * Returns nodes (equipment, transport, decision, saga, classify, hold)
   * and edges (material, exception) with x/y layout for SVG rendering. */
  def getRouteGraph(scenarioId: String) = Action {
    val graph = fabDemoService.getRouteGraph(scenarioId)
    val graphNodes = graph("nodes").asInstanceOf[Seq[Map[String, Any]]]
    val graphEdges = graph("edges").asInstanceOf[Seq[Map[String, String]]]

    val nodes = graphNodes.map { n =>
      Json.obj(
        "id" -> n("id").toString,
        "type" -> n("type").toString,
        "label" -> n("label").toString,
        "meta" -> n.getOrElse("meta", "").toString,
        "x" -> n("x").asInstanceOf[Int],
        "y" -> n("y").asInstanceOf[Int],
        "w" -> n.getOrElse("w", 100).asInstanceOf[Int],
        "h" -> n.getOrElse("h", 44).asInstanceOf[Int],
        "sagaType" -> n.getOrElse("sagaType", "").toString,
        "lotKey" -> n.getOrElse("lotKey", "").toString
      )
    }
    val edges = graphEdges.map { e =>
      val lbl = e.getOrElse("label", "")
      val typ = e.getOrElse("type", "material")
      Json.obj(
        "from" -> e("from"),
        "to" -> e("to"),
        "label" -> lbl,
        "type" -> typ
      )
    }
    Ok(Json.obj(
      "name" -> graph("name").toString,
      "description" -> graph("description").toString,
      "nodes" -> nodes,
      "edges" -> edges
    ))
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

  // ====================================================================
  // M3.5 Self-Healing Demo
  // ====================================================================

  /** Render the M3.5 Self-Healing Demo page. */
  def m35Demo() = Action { implicit request =>
    val langParam = request.getQueryString("lang").getOrElse("")
    val langs: Seq[Lang] = if (langParam.nonEmpty) Seq(Lang(langParam)) else request.acceptLanguages
    val messages = messagesApi.preferred(langs)
    Ok(views.html.fabM35Demo()(messages))
  }

  /** WebSocket endpoint for M3.5 real-time events (reuses existing hubSource). */
  def m35Socket: WebSocket = WebSocket.accept[String, String] { _ =>
    Flow.fromSinkAndSource(Sink.ignore, hubSource.map(frameJson))
  }

  /** Start the M3.5 self-healing demo. */
  def m35Start() = Action.async { implicit request =>
    val scenarioType = request.body.asJson.flatMap(j => (j \ "scenarioType").asOpt[String]).getOrElse("ocap-rework-crash")
    val faultProbability = request.body.asJson.flatMap(j => (j \ "faultProbability").asOpt[Double]).getOrElse(0.2)

    // Look up scenario details for the response
    val scenarioDetails = scenarioType match {
      case "send-ahead-ocap"        => ("Send-Ahead with OCAP", 5)
      case "multi-workorder-chaos"  => ("Multi-WorkOrder Chaos (3 WO)", 15)
      case _                        => ("OCAP Rework + Crash (Self-Healing)", 5)
    }

    fabDemoService.startM35Demo(scenarioType, faultProbability, publishEvent).map { result =>
      Ok(Json.obj(
        "success" -> true,
        "message" -> s"M3.5 demo started: WorkOrder ${result.workOrderId}",
        "workOrderId" -> result.workOrderId,
        "scenarioType" -> scenarioType,
        "scenarioName" -> scenarioDetails._1,
        "waferCount" -> scenarioDetails._2
      ))
    }
  }

  /** Inject a crash for the given workOrderId. */
  def m35InjectCrash(workOrderId: String) = Action.async {
    fabDemoService.injectCrash(workOrderId, publishEvent).map { success =>
      Ok(Json.obj("success" -> success, "workOrderId" -> workOrderId))
    }
  }

  /** Update fault probability mid-demo (P5). */
  def m35UpdateFaultProbability() = Action(parse.json) { request =>
    val probability = request.body.\("probability").asOpt[Double].getOrElse(0.0)
    val clamped = math.max(0.0, math.min(1.0, probability))
    fabDemoService.updateFaultProbability(clamped)
    Ok(Json.obj("success" -> true, "faultProbability" -> clamped))
  }

  /** Get current OCAP rules for the M3.5 demo page. */
  def m35GetOcapRules() = Action {
    val rules = fabDemoService.getOcapRulesForM35
    Ok(Json.toJson(rules.map { r =>
      Json.obj(
        "ruleId" -> r.getOrElse("ruleId", "").toString,
        "name" -> r.getOrElse("name", "").toString,
        "priority" -> (r.getOrElse("priority", 0) match { case i: Int => i; case s => s.toString.toInt }),
        "actionType" -> r.getOrElse("actionType", "").toString,
        "condition" -> r.getOrElse("condition", "").toString,
        "maxTriggersPerLot" -> (r.getOrElse("maxTriggersPerLot", 3) match { case i: Int => i; case s => s.toString.toInt })
      )
    }))
  }

  /** Get recovery status for a work order. */
  def m35RecoveryStatus(id: String) = Action {
    val status = fabDemoService.getRecoveryStatus(id)
    Ok(Json.obj(
      "workOrderId" -> status.getOrElse("workOrderId", "").toString,
      "status" -> status.getOrElse("status", "").toString,
      "recoveryCount" -> (status.getOrElse("recoveryCount", 0) match { case i: Int => i; case s => s.toString.toInt }),
      "lastRecoveryTimeMs" -> (status.getOrElse("lastRecoveryTimeMs", 0L) match { case l: Long => l; case i: Int => i.toLong; case s => s.toString.toLong }),
      "phasesSkipped" -> (status.getOrElse("phasesSkipped", 0) match { case i: Int => i; case s => s.toString.toInt }),
      "eventsReplayed" -> (status.getOrElse("eventsReplayed", 0) match { case i: Int => i; case s => s.toString.toInt })
    ))
  }

  /** Get fault history for a work order. */
  def m35FaultHistory(id: String) = Action {
    val history = fabDemoService.getFaultHistory(id)
    val jsonArr = history.map { h =>
      Json.obj(
        "workOrderId" -> h.getOrElse("workOrderId", "").toString,
        "equipmentId" -> h.getOrElse("equipmentId", "").toString,
        "faultType" -> h.getOrElse("faultType", "").toString,
        "phaseName" -> h.getOrElse("phaseName", "").toString,
        "resolved" -> h.getOrElse("resolved", false).toString,
        "timestamp" -> h.getOrElse("timestamp", 0L).toString
      )
    }
    Ok(Json.toJson(jsonArr))
  }

  // ====================================================================
  // Timeline Query — git-branch-style event replay from journal
  // ====================================================================

  private val readJournal = PersistenceQuery(classicSystem).readJournalFor[ReadJournal with CurrentEventsByPersistenceIdQuery](MongoReadJournal.Identifier)
  private val lotEventAdapter = new LotEventAdapter()

  /** Render the timeline query page. */
  def timelinePage(workOrderId: Option[String]) = Action { implicit request =>
    Ok(views.html.fabTimeline(workOrderId))
  }

  /** JSON endpoint: query all events for a workOrderId across pipeline + all child lots. */
  def timelineQuery(workOrderId: String) = Action.async {
    import scala.concurrent.Future

    if (workOrderId.trim.isEmpty) {
      Future.successful(BadRequest(Json.obj("error" -> "workOrderId required")))
    } else {
      val sourceLotUuid = UUID.nameUUIDFromBytes(s"$workOrderId-source-lot".getBytes)
      val reworkLotUuid = UUID.nameUUIDFromBytes(s"$workOrderId-rework-lot".getBytes)
      val scrapLotUuid  = UUID.nameUUIDFromBytes(s"$workOrderId-scrap-lot".getBytes)
      val pilotLotUuid  = UUID.nameUUIDFromBytes(s"$workOrderId-pilot-lot".getBytes)
      val sampleLotUuid = UUID.nameUUIDFromBytes(s"$workOrderId-sample-lot".getBytes)
      val holdLotUuid   = UUID.nameUUIDFromBytes(s"$workOrderId-hold-lot".getBytes)

      val pipelinePid = s"FabPipelineExecution|$workOrderId"
      val lotIds: List[(String, String)] = List(
        "source" -> s"Lot|${sourceLotUuid.toString}",
        "rework" -> s"Lot|${reworkLotUuid.toString}",
        "scrap"  -> s"Lot|${scrapLotUuid.toString}",
        "pilot"  -> s"Lot|${pilotLotUuid.toString}",
        "sample" -> s"Lot|${sampleLotUuid.toString}",
        "hold"   -> s"Lot|${holdLotUuid.toString}"
      )

      val pipelineEvents: Future[Seq[TimelineEntry]] =
        readJournal.currentEventsByPersistenceId(pipelinePid, 0, Long.MaxValue)
          .map { env =>
            val offset = offsetToLong(env.offset)
            env.event match {
              case e: FabPipelineExecutionActor.Event => pipelineEventToEntry(offset, e)
              case _ => None
            }
          }.collect { case Some(entry) => entry }
          .runWith(Sink.seq)

      val lotQueries: List[Future[Seq[TimelineEntry]]] = lotIds.map { case (branch, pid) =>
        readJournal.currentEventsByPersistenceId(pid, 0, Long.MaxValue)
          .map { env =>
            val offset = offsetToLong(env.offset)
            val evt = env.event match {
              case po: net.imadz.infrastructure.proto.lot.LotEventPO.Event =>
                lotEventAdapter.fromJournal(po, "").events.headOption
              case _ => None
            }
            evt.map(e => TimelineEntry(offset, branch, lotEventName(e), lotEventDetail(e)))
          }
          .collect { case Some(entry) => entry }
          .runWith(Sink.seq)
      }
      val lotBranches: Future[Seq[TimelineEntry]] = Future.sequence(lotQueries).map(_.flatten)

      pipelineEvents.flatMap { pipeline =>
        lotBranches.map { lots =>
          // Group events by branch (preserving journal order within each branch)
          val branchEvents: Map[String, Seq[TimelineEntry]] = (pipeline ++ lots).groupBy(_.branch)
          val sourceEvents = branchEvents.getOrElse("source", Seq.empty)

          // Find SubLotCreated events on source lot → childLotId → index within source lot
          val subLotForkIndex: Map[String, Long] = sourceEvents.zipWithIndex.collect {
            case (entry, idx) if entry.eventType == "SubLotCreated" =>
              // Extract childLotId from detail: "childLot=<id>, reason=..."
              val childIdPattern = "childLot=([a-f0-9]+)".r
              childIdPattern.findFirstMatchIn(entry.detail).map(_.group(1)) -> idx.toLong
          }.collect { case (Some(cid), idx) => cid -> idx }.toMap

          // Child lot ID → branch mapping (from deterministic UUIDs computed above)
          val childIdToBranch: Map[String, String] = Map(
            reworkLotUuid.toString -> "rework",
            scrapLotUuid.toString  -> "scrap",
            pilotLotUuid.toString  -> "pilot",
            sampleLotUuid.toString -> "sample",
            holdLotUuid.toString   -> "hold"
          ).map { case (uuid, branch) => uuid.take(8) -> branch }

          // Resolve fork index for each child branch
          val branchForkIndex: Map[String, Long] = subLotForkIndex.flatMap {
            case (childIdShort, idx) => childIdToBranch.get(childIdShort).map(_ -> idx)
          }

          val SCALE = 100L

          // ---- Fork-Join causal ordering ---------------------------------------
          // Rule: SubLotMerged / SubLotScrapped on parent MUST appear AFTER the
          // child lot's terminal event (LotFailed / LotSealed). Otherwise parent
          // looks like it "knows" the outcome before the child finishes.
          //
          // This generalises to any number of children — each fork-join pair is
          // resolved independently, and source-lot events between fork and outcome
          // are spread proportionally into the child's time window.

          // Child branch → last event index (terminal position)
          val childBranchLastIdx: Map[String, Long] = branchEvents.flatMap {
            case (branch, events) if childIdToBranch.values.toSet.contains(branch) && events.nonEmpty =>
              Some(branch -> (events.size.toLong - 1))
            case _ => None
          }

          // Source-lot fork→outcome pairs: childBranch → (forkIdx, outcomeIdx)
          val sourceOutcomePairs: Map[String, (Long, Long)] = {
            val outcomes: Seq[(String, Long)] = sourceEvents.zipWithIndex.collect {
              case (e, idx) if e.eventType == "SubLotMerged" || e.eventType == "SubLotScrapped" =>
                val childIdPattern = "childLot=([a-f0-9]+)".r
                childIdPattern.findFirstMatchIn(e.detail).map(_.group(1)) -> idx.toLong
            }.collect { case (Some(cid), idx) => cid -> idx }
            outcomes.flatMap { case (cid, outcomeIdx) =>
              childIdToBranch.get(cid).flatMap { branch =>
                branchForkIndex.get(branch).map(forkIdx => branch -> (forkIdx, outcomeIdx))
              }
            }.toMap
          }

          // Source-lot index → (childBranch, forkIdx, outcomeIdx) for active fork-join range
          val sourceForkRange: Map[Long, (String, Long, Long)] = sourceEvents.indices.flatMap { i =>
            val idx = i.toLong
            sourceOutcomePairs.collectFirst {
              case (branch, (fork, outcome)) if idx >= fork && idx <= outcome =>
                idx -> (branch, fork, outcome)
            }
          }.toMap

          // Maximum outcome timestamp (used for post-join events)
          val maxOutcomeTs: Long = sourceOutcomePairs.map { case (branch, (fork, outcome)) =>
            val childLast = childBranchLastIdx.getOrElse(branch, 0L)
            (fork + childLast + 1) * SCALE + (sourceEvents.size.toLong - 1 - outcome) * SCALE
          }.fold(0L)(_ max _)

          // Assign pseudo-timestamps respecting fork-join causal ordering
          val withTs: Seq[TimelineEntry] = branchEvents.toSeq.flatMap {
            case ("source", events) =>
              events.zipWithIndex.map { case (e, i) =>
                val idx = i.toLong
                val ts = sourceForkRange.get(idx) match {
                  case Some((branch, fork, outcome)) =>
                    val childLast = childBranchLastIdx.getOrElse(branch, 0L)
                    if (idx == fork) {
                      // SubLotCreated — at fork point
                      fork * SCALE
                    } else if (idx == outcome) {
                      // SubLotMerged / SubLotScrapped — after child terminal
                      (fork + childLast + 1) * SCALE
                    } else {
                      // Between fork and outcome → spread proportionally into child's window
                      val gapCount = outcome - fork - 1
                      val pos      = idx - fork - 1
                      if (gapCount > 0 && childLast > 0)
                        (fork + 1 + (childLast - 1) * pos / gapCount) * SCALE
                      else
                        (fork + 1) * SCALE
                    }
                  case None =>
                    // After last join → continue sequentially from max outcome
                    val afterAll = sourceOutcomePairs.values.forall { case (_, o) => idx > o }
                    if (afterAll && sourceOutcomePairs.nonEmpty) {
                      val maxO = sourceOutcomePairs.values.map(_._2).max
                      maxOutcomeTs - (sourceEvents.size.toLong - 1 - idx) * SCALE
                    } else {
                      idx * SCALE
                    }
                }
                e.copy(timestamp = ts)
              }

            case (branch, events) if childIdToBranch.values.toSet.contains(branch) =>
              val forkIdx = branchForkIndex.getOrElse(branch, 0L)
              events.zipWithIndex.map { case (e, i) =>
                e.copy(timestamp = (forkIdx + i) * SCALE)
              }

            case (branch, events) =>
              events.zipWithIndex.map { case (e, i) =>
                e.copy(timestamp = i * SCALE)
              }
          }.sortBy(_.timestamp)

          val minTs = withTs.headOption.map(_.timestamp).getOrElse(0L)
          Ok(Json.obj(
            "workOrderId" -> workOrderId,
            "sourceLotId" -> sourceLotUuid.toString,
            "reworkLotId" -> reworkLotUuid.toString,
            "events" -> withTs.map { e =>
              Json.obj(
                "timestamp" -> e.timestamp,
                "branch"    -> e.branch,
                "eventType" -> e.eventType,
                "detail"    -> e.detail,
                "timeStr"   -> formatOffsetTime(e.timestamp, minTs)
              )
            }
          ))
        }
      }.recover { case ex =>
        Ok(Json.obj("error" -> ex.getMessage, "workOrderId" -> workOrderId))
      }
    }
  }

  private case class TimelineEntry(timestamp: Long, branch: String, eventType: String, detail: String)

  private def pipelineEventToEntry(timestamp: Long, evt: FabPipelineExecutionActor.Event): Option[TimelineEntry] = evt match {
    case FabPipelineExecutionActor.Started(scenarioId, woId, stageCount) =>
      Some(TimelineEntry(timestamp, "pipeline", "Started", s"scenario=$scenarioId, stages=$stageCount"))
    case FabPipelineExecutionActor.StageCompleted(phase, ts, metadata, _) =>
      Some(TimelineEntry(timestamp, "pipeline", s"StageCompleted", s"$phase"))
    case FabPipelineExecutionActor.AllCompleted(scenarioId, woId, _, _, _, _) =>
      Some(TimelineEntry(timestamp, "pipeline", "AllCompleted", s"scenario=$scenarioId"))
    case FabPipelineExecutionActor.ExecutionFailed(phase, reason) =>
      Some(TimelineEntry(timestamp, "pipeline", "ExecutionFailed", s"$phase: $reason"))
  }

  private def lotEventName(evt: LotEvent): String = evt.getClass.getSimpleName.replace("$", "")

  private def lotEventDetail(evt: LotEvent): String = evt match {
    case e: net.imadz.domain.entities.LotEntity.LotCreated => s"product=${e.productId}"
    case e: net.imadz.domain.entities.LotEntity.WaferRemovalCommitted => s"transferId=${e.transferId}, wafers=${e.waferNames.mkString(",")}"
    case e: net.imadz.domain.entities.LotEntity.WaferAdditionCommitted => s"transferId=${e.transferId}"
    case e: net.imadz.domain.entities.LotEntity.TransportStarted => s"${e.fromArea}->${e.toArea}"
    case e: net.imadz.domain.entities.LotEntity.TransportCompleted => s"${e.equipmentId}"
    case e: net.imadz.domain.entities.LotEntity.EquipmentJobStarted => s"${e.equipmentId}, recipe=${e.recipeId}"
    case e: net.imadz.domain.entities.LotEntity.EquipmentJobCompleted => s"${e.equipmentId}, success=${e.success}"
    case e: net.imadz.domain.entities.LotEntity.WaferMeasured => s"wafer=${e.waferId}, cd=${e.cdNm}nm"
    case e: net.imadz.domain.entities.LotEntity.WaferClassified => s"wafer=${e.waferId}, ${e.classification}, cd=${e.cdValue}nm"
    case e: net.imadz.domain.entities.LotEntity.WafersSplitForRework => s"rework=${e.reworkWaferIds.mkString(",")}, iteration=${e.iteration}"
    case e: net.imadz.domain.entities.LotEntity.SubLotCreated => s"childLot=${e.childLotId.toString.take(8)}, reason=${e.splitReason}, wafers=${e.waferIds.size}"
    case e: net.imadz.domain.entities.LotEntity.SubLotMerged => s"childLot=${e.childLotId.toString.take(8)}, wafers=${e.waferIds.size}"
    case e: net.imadz.domain.entities.LotEntity.SubLotScrapped => s"childLot=${e.childLotId.toString.take(8)}, reason=${e.reason}, wafers=${e.waferIds.size}"
    case e: net.imadz.domain.entities.LotEntity.WafersReworked => s"wafers=${e.waferIds.mkString(",")}"
    case e: net.imadz.domain.entities.LotEntity.ProcessCompleted => s"pass=${e.passCount}, scrap=${e.scrapCount}, rework=${e.reworkCount}"
    case e: net.imadz.domain.entities.LotEntity.LotFailed => s"${e.reason} @ ${e.failedAt}"
    case e: net.imadz.domain.entities.LotEntity.LotSealed => ""
    case e: net.imadz.domain.entities.LotEntity.RouteCardAssigned => s"steps=${e.steps.size}"
    case e: net.imadz.domain.entities.LotEntity.RouteCardStepAdvanced => s"step=${e.stepIndex}"
    case _ => ""
  }

  private def offsetToLong(offset: akka.persistence.query.Offset): Long = offset match {
    case Sequence(v) => v
    case _           => 0L
  }

  private def formatOffsetTime(offset: Long, minOffset: Long): String = {
    val rel = offset - minOffset
    val totalMs = rel * 100L
    val s = totalMs / 1000L
    val ms = totalMs % 1000L
    f"T+$s%02d.$ms%03d"
  }

  // ====================================================================
  // Route CRUD (M3.5+)
  // ====================================================================

  /** List all route IDs with latest version info */
  def listRoutes = Action {
    val routeIds = RouteDefinitionStore.listRouteIds()
    val routes = routeIds.map { rid =>
      RouteDefinitionStore.getLatest(rid).map { r =>
        Json.obj("routeId" -> r.routeId, "version" -> r.version, "name" -> r.name,
          "productId" -> r.productId, "nodeCount" -> r.nodes.size, "edgeCount" -> r.edges.size)
      }
    }
    Ok(Json.obj("routes" -> Json.toJson(routes.flatten)))
  }

  /** Get latest version of a route definition */
  def getRoute(id: String) = Action {
    RouteDefinitionStore.getLatest(id) match {
      case Some(r) => Ok(routeDefToJson(r))
      case None    => NotFound(Json.obj("error" -> s"Route $id not found"))
    }
  }

  /** List all versions of a route */
  def listRouteVersions(id: String) = Action {
    val versions = RouteDefinitionStore.listVersions(id)
    Ok(Json.obj("routeId" -> id, "versions" -> versions))
  }

  /** Get a specific version of a route */
  def getRouteVersion(id: String, version: Int) = Action {
    RouteDefinitionStore.get(id, version) match {
      case Some(r) => Ok(routeDefToJson(r))
      case None    => NotFound(Json.obj("error" -> s"Route $id v$version not found"))
    }
  }

  /** Publish a new RouteDefinition */
  def publishRoute = Action(parse.json) { request =>
    val json = request.body
    try {
      val route = parseRouteDef(json)
      val published = RouteDefinitionStore.publish(route)
      Created(Json.obj("success" -> true, "routeId" -> published.routeId, "version" -> published.version))
    } catch {
      case ex: Exception => BadRequest(Json.obj("error" -> ex.getMessage))
    }
  }

  /** Compile a route to preview the steps */
  def compileRoute(id: String) = Action {
    RouteDefinitionStore.getLatest(id) match {
      case Some(route) =>
        val stages = RouteCompiler.compile(route)
        val steps = stages.map { s =>
          val name = s.getClass.getSimpleName.replace("$", "")
          name
        }
        Ok(Json.obj("routeId" -> id, "version" -> route.version, "stepCount" -> stages.size, "steps" -> steps))
      case None => NotFound(Json.obj("error" -> s"Route $id not found"))
    }
  }

  /** Start a demo from a RouteDefinition (Route Browser "Start" button) */
  def startRouteDemo(id: String) = Action.async {
    fabDemoService.startDemoFromRoute(id, publishEvent).map { result =>
      Ok(Json.obj("success" -> true, "message" -> s"WorkOrder ${result.workOrderId} ${result.phase}", "workOrderId" -> result.workOrderId))
    }
  }

  /** Seed default routes into Repository (idempotent) */
  def seedDefaultRoutes = Action {
    fabDemoService.seedDefaultRoutes()
    Ok(Json.obj("success" -> true, "routes" -> RouteDefinitionStore.listRouteIds()))
  }

  private def routeDefToJson(r: RouteDefinition): JsValue = Json.obj(
    "routeId" -> r.routeId, "version" -> r.version, "name" -> r.name,
    "productId" -> r.productId, "description" -> r.description,
    "nodeCount" -> r.nodes.size, "edgeCount" -> r.edges.size,
    "ocapRuleCount" -> r.ocapRules.size
  )

  private def parseRouteDef(json: JsValue): RouteDefinition = {
    val routeId = (json \ "routeId").as[String]
    val productId = (json \ "productId").as[String]
    val version = (json \ "version").asOpt[Int].getOrElse(0)
    val name = (json \ "name").as[String]
    val desc = (json \ "description").asOpt[String].getOrElse("")
    // Parse nodes from JSON
    val nodes = (json \ "nodes").as[Seq[JsValue]].map { n =>
      val ntype = (n \ "type").as[String]
      val nid = (n \ "nodeId").as[String]
      val label = (n \ "label").as[String]
      ntype match {
        case "atomic" =>
          val opType = parseAtomicOp((n \ "operationType").as[String])
          val config = (n \ "config").asOpt[Map[String, String]].getOrElse(Map.empty)
          AtomicStep(nid, label, opType, config)
        case "decision" =>
          val condJson = (n \ "condition").as[JsValue]
          val metric = (condJson \ "metric").as[String]
          val op = parseComparisonOp((condJson \ "operator").as[String])
          val lower = (condJson \ "lowerBound").as[Double]
          val upper = (condJson \ "upperBound").as[Double]
          DecisionNode(nid, label, MeasurementCondition(metric, op, lower, upper))
        case "saga" =>
          val sagaType = (n \ "sagaType").as[String] match {
            case "split" => SagaSplitOp; case "merge" => SagaMergeOp
          }
          val lotKey = (n \ "lotKey").as[String]
          SagaStep(nid, label, sagaType, lotKey, FixedCount(1))
        case "subprocess" =>
          val subType = (n \ "subProcessType").as[String] match {
            case "send-ahead-pilot" => SendAheadPilot
            case "rework-loop"     => ReworkLoop
            case "hold-release"    => HoldRelease
            case "sampling"        => Sampling
            case "scrap-downgrade" => ScrapDowngrade
          }
          val params = (n \ "params").asOpt[Map[String, String]].getOrElse(Map.empty)
          SubProcessRef(nid, label, subType, params)
        case _ => AtomicStep(nid, label, LoadFoupOp)
      }
    }.toList
    // Parse edges
    val edges = (json \ "edges").as[Seq[JsValue]].map { e =>
      RouteEdge(
        edgeId = (e \ "edgeId").as[String],
        sourceNodeId = (e \ "source").as[String],
        targetNodeId = (e \ "target").as[String],
        edgeType = (e \ "type").asOpt[String].getOrElse("material") match {
          case "material" => MaterialFlow; case "exception" => ExceptionFlow
          case "ocap" => OcapFlow; case _ => MaterialFlow
        },
        label = (e \ "label").asOpt[String].getOrElse("")
      )
    }.toList
    RouteDefinition(routeId, productId, version, name, desc, nodes, edges, Nil)
  }

  private def parseAtomicOp(s: String): AtomicOperationType = s match {
    case "LoadFoupOp"     => LoadFoupOp
    case "TransportOp"    => TransportOp
    case "AtEquipmentOp"  => AtEquipmentOp
    case "TrackInOp"      => TrackInOp
    case "TrackOutOp"     => TrackOutOp
    case "RunRecipeOp"    => RunRecipeOp
    case "MeasureOp"      => MeasureOp
    case "ClassifyOp"     => ClassifyOp
    case "SealCompleteOp" => SealCompleteOp
    case "HoldWafersOp"   => HoldWafersOp
    case "ReleaseWafersOp"=> ReleaseWafersOp
  }

  private def parseComparisonOp(s: String): ComparisonOp = s match {
    case "GreaterThan"        => GreaterThan;        case "LessThan"        => LessThan
    case "GreaterThanOrEqual" => GreaterThanOrEqual; case "LessThanOrEqual" => LessThanOrEqual
    case "WithinRange"        => WithinRange;         case "OutsideRange"   => OutsideRange
  }
}
