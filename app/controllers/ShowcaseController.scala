package controllers

import akka.actor.typed.ActorSystem
import akka.actor.typed.eventstream.EventStream.Subscribe
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.scaladsl.{CurrentEventsByPersistenceIdQuery, ReadJournal}
import akka.stream.Materializer
import akka.stream.scaladsl.{BroadcastHub, Keep, MergeHub, Sink, Source}
import akka.contrib.persistence.mongodb.MongoReadJournal
import net.imadz.application.services.transactor.{ShowcaseParticipant, ShowcaseSagaDefinition}
import net.imadz.infra.saga.SagaProgressEvent
import net.imadz.infra.saga.SagaTransactionCoordinator
import net.imadz.infra.saga.persistence.{SagaTransactionCoordinatorEventAdapter, StepExecutorEventAdapter}
import net.imadz.infra.saga.proto.saga_v3.{SagaTransactionCoordinatorEventPO, StepExecutorEventPO}
import net.imadz.infrastructure.bootstrap.SagaEngineBootstrap
import play.api.libs.json.{JsNull, JsString, JsValue, Json, Writes}
import play.api.mvc._

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

@Singleton
class ShowcaseController @Inject()(val controllerComponents: ControllerComponents,
                                    implicit val system: akka.actor.ActorSystem,
                                    implicit val mat: Materializer,
                                    implicit val ec: ExecutionContext) extends BaseController {

  private val readJournal = PersistenceQuery(system).readJournalFor[ReadJournal with CurrentEventsByPersistenceIdQuery](MongoReadJournal.Identifier)
  implicit val typedSystem: ActorSystem[Nothing] = system.toTyped
  private implicit val scheduler: akka.actor.typed.Scheduler = typedSystem.scheduler

  // --- Saga runner (engine sharding is initialized in ApplicationBootstrap) ---
  private val sharding = ClusterSharding(typedSystem)
  private val runner = ShowcaseSagaDefinition.runner(typedSystem, txId => sharding.entityRefFor(SagaTransactionCoordinator.entityTypeKey, txId))

  // --- JSON Serialization ---
  implicit val sagaProgressEventWrites: Writes[SagaProgressEvent] = Writes {
    case e: SagaProgressEvent.TransactionStarted => Json.obj("type" -> "TransactionStarted", "data" -> Json.obj("transactionId" -> e.transactionId, "steps" -> e.steps, "traceId" -> e.traceId))
    case e: SagaProgressEvent.StepOngoing => Json.obj("type" -> "StepOngoing", "data" -> Json.obj("transactionId" -> e.transactionId, "stepId" -> e.stepId, "phase" -> e.phase, "traceId" -> e.traceId))
    case e: SagaProgressEvent.StepCompleted => Json.obj("type" -> "StepCompleted", "data" -> Json.obj("transactionId" -> e.transactionId, "stepId" -> e.stepId, "phase" -> e.phase, "traceId" -> e.traceId, "isManual" -> e.isManual))
    case e: SagaProgressEvent.StepFailed => Json.obj("type" -> "StepFailed", "data" -> Json.obj("transactionId" -> e.transactionId, "stepId" -> e.stepId, "phase" -> e.phase, "error" -> e.error, "traceId" -> e.traceId))
    case e: SagaProgressEvent.TransactionCompleted => Json.obj("type" -> "TransactionCompleted", "data" -> Json.obj("transactionId" -> e.transactionId, "traceId" -> e.traceId))
    case e: SagaProgressEvent.TransactionFailed => Json.obj("type" -> "TransactionFailed", "data" -> Json.obj("transactionId" -> e.transactionId, "reason" -> e.reason, "traceId" -> e.traceId))
    case e: SagaProgressEvent.TransactionSuspended => Json.obj("type" -> "TransactionSuspended", "data" -> Json.obj("transactionId" -> e.transactionId, "reason" -> e.reason, "traceId" -> e.traceId))
  }

  // --- Real-time WebSocket ---
  private val (hubSink, hubSource) = MergeHub.source[SagaProgressEvent].toMat(BroadcastHub.sink[SagaProgressEvent])(Keep.both).run()
  private val bridgeActor = system.actorOf(akka.actor.Props(new akka.actor.Actor {
    override def preStart(): Unit = { system.eventStream.subscribe(self, classOf[SagaProgressEvent]) }
    def receive: Receive = { case e: SagaProgressEvent => Source.single(e).runWith(hubSink) }
  }))

  def socket = WebSocket.accept[String, String] { request =>
    akka.stream.scaladsl.Flow.fromSinkAndSource(Sink.ignore, hubSource.map(e => Json.toJson(e)(sagaProgressEventWrites).toString()))
  }

  // --- Actions ---
  def index() = Action { implicit request: Request[AnyContent] => Ok(views.html.showcase()) }

  def injectFault(stepId: String, behavior: String) = Action {
    val b = behavior.toLowerCase match {
      case "success" => ShowcaseParticipant.Success
      case "failretryable" => ShowcaseParticipant.FailRetryable
      case "failnonretryable" => ShowcaseParticipant.FailNonRetryable
      case "timeout" => ShowcaseParticipant.Timeout
      case "failtwicethensucceed" => ShowcaseParticipant.FailTwiceThenSucceed
      case _ => ShowcaseParticipant.Success
    }
    ShowcaseParticipant.setBehavior(stepId, b)
    Ok(Json.obj("status" -> "ok", "stepId" -> stepId, "behavior" -> behavior))
  }

  def triggerShowcase(singleStep: Boolean) = Action { implicit request: Request[AnyContent] =>
    val transactionId = java.util.UUID.randomUUID().toString
    val traceId = s"TRACE-${transactionId.substring(0, 8)}"
    // The saga definition lives in the registry; start it through the runner.
    runner.run(transactionId, ShowcaseSagaDefinition.ShowcaseArgs(), traceId, singleStep)
    Ok(Json.obj("status" -> "ok", "transactionId" -> transactionId, "traceId" -> traceId))
  }

  /** Admin ops ride the runner so the completion channel stays attached. */
  def proceed(transactionId: String) = Action.async { implicit request: Request[AnyContent] =>
    runner.admin.proceed(transactionId).map(r => Ok(resultJson(transactionId, r)))
      .recover { case ex => Ok(Json.obj("status" -> "timeout", "transactionId" -> transactionId, "error" -> ex.getMessage)) }
  }

  def fixStep(transactionId: String, stepId: String, phase: String) = Action { implicit request: Request[AnyContent] =>
    import net.imadz.infra.saga.SagaPhase
    val p = phase.toLowerCase match { case "prepare" => SagaPhase.PreparePhase; case "commit" => SagaPhase.CommitPhase; case "compensate" => SagaPhase.CompensatePhase; case _ => SagaPhase.PreparePhase }
    runner.admin.fixStep(transactionId, stepId, p)
    Ok(Json.obj("status" -> "ok", "transactionId" -> transactionId, "stepId" -> stepId, "phase" -> phase))
  }

  def resume(transactionId: String) = Action.async { implicit request: Request[AnyContent] =>
    runner.admin.resolveSuspended(transactionId).map(r => Ok(resultJson(transactionId, r)))
      .recover { case ex => Ok(Json.obj("status" -> "timeout", "transactionId" -> transactionId, "error" -> ex.getMessage)) }
  }

  def retryPhase(transactionId: String) = Action.async { implicit request: Request[AnyContent] =>
    runner.admin.retryPhase(transactionId).map(r => Ok(resultJson(transactionId, r)))
      .recover { case ex => Ok(Json.obj("status" -> "timeout", "transactionId" -> transactionId, "error" -> ex.getMessage)) }
  }

  def showcaseStatus(transactionId: String) = Action.async { implicit request: Request[AnyContent] =>
    runner.statusOf(transactionId).map {
      case Some(snapshot) => Ok(Json.obj(
        "transactionId" -> snapshot.transactionId,
        "status" -> snapshot.status,
        "currentPhase" -> snapshot.currentPhase,
        "currentStepGroup" -> snapshot.currentStepGroup,
        "isPaused" -> snapshot.isPaused,
        "failReason" -> snapshot.failReason.map(JsString(_)).getOrElse[JsValue](JsNull),
        "steps" -> snapshot.steps.map(st => Json.obj(
          "stepId" -> st.stepId, "phase" -> st.phase, "status" -> st.status, "retries" -> st.retries))))
      case None => NotFound(Json.obj("error" -> s"unknown transaction $transactionId"))
    }
  }

  // --- Historical Replay API (saga_v3 journal: coordinator + executor pids) ---
  def getHistory(transactionId: String) = Action.async {
    val coordAdapter = new SagaTransactionCoordinatorEventAdapter(system.asInstanceOf[akka.actor.ExtendedActorSystem])
    val stepAdapter = new StepExecutorEventAdapter(system.asInstanceOf[akka.actor.ExtendedActorSystem])
    val coordinatorPid = s"${SagaEngineBootstrap.CoordinatorPidPrefix}$transactionId"

    readJournal.currentEventsByPersistenceId(coordinatorPid, 0, Long.MaxValue).runWith(Sink.seq).flatMap { coordEnvelopes =>
      val coordEventsWithTs = coordEnvelopes.flatMap { env =>
        val evt = env.event match {
          case po: SagaTransactionCoordinatorEventPO => coordAdapter.fromJournal(po, "").events.headOption
          case other => Some(other.asInstanceOf[SagaTransactionCoordinator.Event])
        }
        evt.map(e => (env.timestamp, e))
      }

      // saga_v3: the coordinator journal carries static step descriptors
      val stepsDef = coordEventsWithTs.map(_._2).collectFirst {
        case e: SagaTransactionCoordinator.TransactionStarted => e.steps
      }.getOrElse(Nil)

      val queryTasks = for {
        s <- stepsDef
        p <- List(net.imadz.infra.saga.SagaPhase.PreparePhase, net.imadz.infra.saga.SagaPhase.CommitPhase, net.imadz.infra.saga.SagaPhase.CompensatePhase)
        suffix <- List(p.toString, p.toString.toLowerCase.replace("phase", ""))
      } yield (s"${SagaEngineBootstrap.StepExecutorPidPrefix}$transactionId-${s.stepId}-$suffix", s.stepId, p.toString.toLowerCase.replace("phase", ""))

      Future.sequence(queryTasks.distinct.map { case (pid, sid, ph) =>
        readJournal.currentEventsByPersistenceId(pid, 0, Long.MaxValue).map { env =>
          val evt = env.event match {
            case po: StepExecutorEventPO => stepAdapter.fromJournal(po, "").events.headOption
            case other => Some(other.asInstanceOf[net.imadz.infra.saga.StepExecutor.Event])
          }
          (env.timestamp, sid, ph, evt)
        }.runWith(Sink.seq)
      }).map { stepResults =>
        val allStepEvts = stepResults.flatten.collect { case (ts, sid, ph, Some(evt)) => (ts, sid, ph, evt) }
        val history = buildHistory(transactionId, coordEventsWithTs, allStepEvts)
        Ok(Json.toJson(history))
      }
    }
  }

  private def resultJson(transactionId: String, r: SagaTransactionCoordinator.TransactionResult): JsValue = Json.obj(
    "status" -> "ok",
    "transactionId" -> transactionId,
    "successful" -> r.successful,
    "transactionStatus" -> r.snapshot.status,
    "failReason" -> (if (r.failReason == null || r.failReason.isEmpty) JsNull else JsString(r.failReason)))

  private def buildHistory(txId: String, coordWithTs: Seq[(Long, net.imadz.infra.saga.SagaTransactionCoordinator.Event)], stepEvts: Seq[(Long, String, String, net.imadz.infra.saga.StepExecutor.Event)]): Seq[JsValue] = {
    import net.imadz.infra.saga.SagaTransactionCoordinator._
    import net.imadz.infra.saga.StepExecutor._

    val coordEnvelopes = coordWithTs.map { case (ts, evt) =>
      val pEvt = evt match {
        case e: TransactionStarted => SagaProgressEvent.TransactionStarted(e.transactionId, e.steps.map(_.stepId), e.traceId)
        case e: TransactionCompleted => SagaProgressEvent.TransactionCompleted(e.transactionId, "")
        case e: TransactionFailed => SagaProgressEvent.TransactionFailed(e.transactionId, e.reason, "")
        case e: TransactionSuspended => SagaProgressEvent.TransactionSuspended(e.transactionId, e.reason, "")
        case _ => null
      }
      (ts, pEvt)
    }

    val stepEnvelopes = stepEvts.map { case (ts, sid, ph, evt) =>
      val pEvt = evt match {
        case _: ExecutionStarted => SagaProgressEvent.StepOngoing(txId, sid, ph, "")
        case OperationSucceeded(_) => SagaProgressEvent.StepCompleted(txId, sid, ph, "", isManual = false)
        case ManualFixCompleted(_) => SagaProgressEvent.StepCompleted(txId, sid, ph, "", isManual = true)
        case OperationFailed(err) => SagaProgressEvent.StepFailed(txId, sid, ph, err.message, "")
        case RetryScheduled(c) => SagaProgressEvent.StepFailed(txId, sid, ph, s"Retry #$c", "")
        case _ => null
      }
      (ts, pEvt)
    }

    (coordEnvelopes ++ stepEnvelopes).filter(_._2 != null).sortBy { case (ts, evt) =>
      // 因果律权重系统：物理时间戳为大背景，相位优先级解决微小偏差
      val phasePriority = evt match {
        case _: SagaProgressEvent.TransactionStarted => 0
        case e: SagaProgressEvent.StepOngoing => getPhaseWeight(e.phase)
        case e: SagaProgressEvent.StepCompleted => getPhaseWeight(e.phase) + 1
        case e: SagaProgressEvent.StepFailed => getPhaseWeight(e.phase) + 1
        case _: SagaProgressEvent.TransactionCompleted | _: SagaProgressEvent.TransactionFailed | _: SagaProgressEvent.TransactionSuspended => 1000
        case _ => 500
      }
      (ts, phasePriority)
    }.map { case (ts, evt) =>
      Json.obj("timestamp" -> ts, "event" -> Json.toJson(evt.asInstanceOf[SagaProgressEvent])(sagaProgressEventWrites))
    }
  }

  private def getPhaseWeight(phase: String): Int = phase.toLowerCase match {
    case p if p.contains("prepare") => 100
    case p if p.contains("commit") => 200
    case p if p.contains("compensate") => 300
    case _ => 400
  }
}
