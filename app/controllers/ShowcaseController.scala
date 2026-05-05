package controllers

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.scaladsl.{CurrentEventsByPersistenceIdQuery, ReadJournal}
import akka.stream.Materializer
import akka.stream.scaladsl.{BroadcastHub, Keep, MergeHub, Sink, Source}
import akka.contrib.persistence.mongodb.MongoReadJournal
import net.imadz.application.services.transactor.DynamicShowcaseParticipant
import net.imadz.application.events.SagaProgressEvent
import net.imadz.infra.saga.{SagaPhase, SagaTransactionCoordinator, SagaTransactionStep, StepExecutor}
import play.api.i18n.{I18nSupport, Lang, Messages}
import play.api.libs.json.{JsValue, Json, Writes}
import play.api.mvc._

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ShowcaseController @Inject()(val controllerComponents: ControllerComponents,
                                   implicit val system: akka.actor.ActorSystem,
                                   implicit val mat: Materializer,
                                   implicit val ec: ExecutionContext) extends BaseController with I18nSupport {

  private val readJournal = PersistenceQuery(system).readJournalFor[ReadJournal with CurrentEventsByPersistenceIdQuery](MongoReadJournal.Identifier)
  implicit val typedSystem: ActorSystem[Nothing] = system.toTyped

  // --- JSON Serialization ---
  implicit val stepInfoWrites: Writes[SagaProgressEvent.StepInfo] = Json.writes[SagaProgressEvent.StepInfo]
  implicit val sagaProgressEventWrites: Writes[SagaProgressEvent] = Writes {
    case e: SagaProgressEvent.TransactionStarted => Json.obj("type" -> "TransactionStarted", "data" -> Json.obj("transactionId" -> e.transactionId, "steps" -> e.steps, "traceId" -> e.traceId))
    case e: SagaProgressEvent.StepOngoing => Json.obj("type" -> "StepOngoing", "data" -> Json.obj("transactionId" -> e.transactionId, "stepId" -> e.stepId, "phase" -> e.phase, "traceId" -> e.traceId))
    case e: SagaProgressEvent.StepCompleted => Json.obj("type" -> "StepCompleted", "data" -> Json.obj("transactionId" -> e.transactionId, "stepId" -> e.stepId, "phase" -> e.phase, "traceId" -> e.traceId, "isManual" -> e.isManual))
    case e: SagaProgressEvent.StepFailed => Json.obj("type" -> "StepFailed", "data" -> Json.obj("transactionId" -> e.transactionId, "stepId" -> e.stepId, "phase" -> e.phase, "error" -> e.error, "traceId" -> e.traceId))
    case e: SagaProgressEvent.PhaseStarted => Json.obj("type" -> "PhaseStarted", "data" -> Json.obj("transactionId" -> e.transactionId, "phase" -> e.phase, "traceId" -> e.traceId))
    case e: SagaProgressEvent.PhaseCompleted => Json.obj("type" -> "PhaseCompleted", "data" -> Json.obj("transactionId" -> e.transactionId, "phase" -> e.phase, "traceId" -> e.traceId))
    case e: SagaProgressEvent.StepGroupStarted => Json.obj("type" -> "StepGroupStarted", "data" -> Json.obj("transactionId" -> e.transactionId, "phase" -> e.phase, "group" -> e.group, "traceId" -> e.traceId))
    case e: SagaProgressEvent.TransactionCompleted => Json.obj("type" -> "TransactionCompleted", "data" -> Json.obj("transactionId" -> e.transactionId, "traceId" -> e.traceId))
    case e: SagaProgressEvent.TransactionFailed => Json.obj("type" -> "TransactionFailed", "data" -> Json.obj("transactionId" -> e.transactionId, "reason" -> e.reason, "traceId" -> e.traceId))
    case e: SagaProgressEvent.TransactionSuspended => Json.obj("type" -> "TransactionSuspended", "data" -> Json.obj("transactionId" -> e.transactionId, "reason" -> e.reason, "traceId" -> e.traceId))
    case e: SagaProgressEvent.DomainEventPublished => Json.obj("type" -> e.eventType, "data" -> Json.obj("transactionId" -> e.transactionId, "detail" -> e.detail, "traceId" -> e.traceId, "isDomainEvent" -> true))
  }

  // --- Real-time WebSocket ---
  private val (hubSink, hubSource) = MergeHub.source[SagaProgressEvent].toMat(BroadcastHub.sink[SagaProgressEvent])(Keep.both).run()

  // Typed Bridge Actor
  private val bridge = typedSystem.systemActorOf(akka.actor.typed.scaladsl.Behaviors.setup[Any] { context =>
    import akka.actor.typed.scaladsl.adapter._
    
    // Use classic event stream for polymorphic sub-type subscription
    context.system.toClassic.eventStream.subscribe(context.self.toClassic, classOf[SagaTransactionCoordinator.Event])
    context.system.toClassic.eventStream.subscribe(context.self.toClassic, classOf[StepExecutor.Event])
    context.system.toClassic.eventStream.subscribe(context.self.toClassic, classOf[net.imadz.domain.entities.CreditBalanceEntity.CreditBalanceEvent])

    // Also subscribe to Typed EventStream as some components publish there
    context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Subscribe[SagaTransactionCoordinator.Event](context.self)
    context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Subscribe[StepExecutor.Event](context.self)
    context.system.eventStream ! akka.actor.typed.eventstream.EventStream.Subscribe[net.imadz.domain.entities.CreditBalanceEntity.CreditBalanceEvent](context.self)

    akka.actor.typed.scaladsl.Behaviors.receiveMessage {
      case e: SagaTransactionCoordinator.Event =>
        println(s"[Saga Bridge] Received Coordinator Event: ${e.getClass.getSimpleName}")
        val pEvt = e match {
          case ex: SagaTransactionCoordinator.TransactionStarted =>
            val stepsInfo = ex.steps.map(s => SagaProgressEvent.StepInfo(s.stepId, s.stepGroup)).distinct
            SagaProgressEvent.TransactionStarted(ex.transactionId, stepsInfo, ex.traceId)
          case ex: SagaTransactionCoordinator.PhaseSucceeded => SagaProgressEvent.PhaseCompleted(ex.transactionId, ex.phase.toString, "")
          case ex: SagaTransactionCoordinator.PhaseFailed => SagaProgressEvent.PhaseCompleted(ex.transactionId, ex.phase.toString, "")
          case ex: SagaTransactionCoordinator.StepGroupStarted => SagaProgressEvent.StepGroupStarted(ex.transactionId, ex.phase.toString, ex.group, "")
          case ex: SagaTransactionCoordinator.TransactionCompleted => SagaProgressEvent.TransactionCompleted(ex.transactionId, "")
          case ex: SagaTransactionCoordinator.TransactionFailed => SagaProgressEvent.TransactionFailed(ex.transactionId, ex.reason, "")
          case ex: SagaTransactionCoordinator.TransactionSuspended => SagaProgressEvent.TransactionSuspended(ex.transactionId, ex.reason, "")
          case _ => null
        }
        if (pEvt != null) Source.single(pEvt).runWith(hubSink)
        akka.actor.typed.scaladsl.Behaviors.same

      case e: StepExecutor.Event =>
        println(s"[Saga Bridge] Received Executor Event: ${e.getClass.getSimpleName}")
        val pEvt = e match {
          case ex: StepExecutor.ExecutionStarted[_, _, _] => SagaProgressEvent.StepOngoing(ex.transactionId, ex.stepId, ex.phase.toString, ex.traceId)
          case ex: StepExecutor.OperationSucceeded[_] => SagaProgressEvent.StepCompleted(ex.transactionId, ex.stepId, ex.phase.toString, ex.traceId, isManual = false)
          case ex: StepExecutor.ManualFixCompleted[_] => SagaProgressEvent.StepCompleted(ex.transactionId, ex.stepId, ex.phase.toString, ex.traceId, isManual = true)
          case StepExecutor.OperationFailed(txId, sid, ph, tid, err) => SagaProgressEvent.StepFailed(txId, sid, ph.toString, err.message, tid)
          case StepExecutor.RetryScheduled(txId, sid, ph, tid, c) => SagaProgressEvent.StepFailed(txId, sid, ph.toString, s"Retry #$c", tid)
          case _ => null
        }
        if (pEvt != null) Source.single(pEvt).runWith(hubSink)
        akka.actor.typed.scaladsl.Behaviors.same

      case e: net.imadz.domain.entities.CreditBalanceEntity.CreditBalanceEvent =>
        import net.imadz.domain.entities.CreditBalanceEntity._
        println(s"[Saga Bridge] Received CreditBalance Event: ${e.getClass.getSimpleName}")
        val pEvt = e match {
          case ex: BalanceChanged => SagaProgressEvent.DomainEventPublished("", "BalanceChanged", s"Update: ${ex.update.amount}", "")
          case ex: FundsReserved => SagaProgressEvent.DomainEventPublished(ex.transferId.toString, "FundsReserved", s"Amount: ${ex.amount.amount}", "")
          case ex: FundsDeducted => SagaProgressEvent.DomainEventPublished(ex.transferId.toString, "FundsDeducted", s"Amount: ${ex.amount.amount}", "")
          case ex: ReservationReleased => SagaProgressEvent.DomainEventPublished(ex.transferId.toString, "ReservationReleased", s"Amount: ${ex.amount.amount}", "")
          case ex: IncomingCreditsRecorded => SagaProgressEvent.DomainEventPublished(ex.transferId.toString, "IncomingCreditsRecorded", s"Amount: ${ex.amount.amount}", "")
          case ex: IncomingCreditsCommited => SagaProgressEvent.DomainEventPublished(ex.transferId.toString, "IncomingCreditsCommited", "", "")
          case ex: IncomingCreditsCanceled => SagaProgressEvent.DomainEventPublished(ex.transferId.toString, "IncomingCreditsCanceled", "", "")
        }
        if (pEvt != null) Source.single(pEvt).runWith(hubSink)
        akka.actor.typed.scaladsl.Behaviors.same
      
      case _ => akka.actor.typed.scaladsl.Behaviors.same
    }
  }, "SagaEventBridge")

  // --- Actions ---
  def sagaDocs(page: String) = Action { implicit request =>
    val lang = messagesApi.preferred(request).lang.code
    val suffix = if (lang == "zh") "_zh" else ""

    val fileName = page.toLowerCase match {
      case "overview" => s"index$suffix.md"
      case "architecture" => s"architecture$suffix.md"
      case "guide" => s"usage_guide$suffix.md"
      case _ => s"index$suffix.md"
    }

    val filePath = s"knowledge_base/saga_framework/$fileName"
    val content = try {
      val source = scala.io.Source.fromFile(filePath)
      val text = source.mkString
      source.close()
      text
    } catch {
      case _: Exception => s"# Error\n\nDocument '$page' not found at $filePath."
    }

    Ok(views.html.sagaDocs(page, content)).withHeaders(
      "Content-Security-Policy" -> "default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval' https://cdnjs.cloudflare.com https://cdn.jsdelivr.net; style-src 'self' 'unsafe-inline' https://cdnjs.cloudflare.com; img-src 'self' data:; connect-src 'self' ws: wss:;"
    )
  }

  def index() = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.showcase()).withHeaders(
      "Content-Security-Policy" -> "default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval' https://cdnjs.cloudflare.com https://cdn.jsdelivr.net; style-src 'self' 'unsafe-inline' https://cdnjs.cloudflare.com; img-src 'self' data:; connect-src 'self' ws: wss:;"
    )
  }

  def setLang(code: String) = Action { implicit request =>
    val redirectUrl = request.headers.get("Referer").getOrElse("/showcase")
    Redirect(redirectUrl).withLang(Lang(code))
  }

  case class Scenario(id: String, name: String, steps: List[SagaTransactionStep[Any, String, Any]], behaviors: Map[String, DynamicShowcaseParticipant.Behavior])

  private val scenarios: Map[String, Scenario] = Map(
    "happy" -> Scenario("happy", "Happy Path (Success)", List(
      SagaTransactionStep[Any, String, Any]("Step-A", SagaPhase.PreparePhase, new DynamicShowcaseParticipant("Step-A"), 3, stepGroup = 1),
      SagaTransactionStep[Any, String, Any]("Step-B", SagaPhase.PreparePhase, new DynamicShowcaseParticipant("Step-B"), 3, stepGroup = 2),
      SagaTransactionStep[Any, String, Any]("Step-A", SagaPhase.CommitPhase, new DynamicShowcaseParticipant("Step-A"), 3, stepGroup = 1),
      SagaTransactionStep[Any, String, Any]("Step-B", SagaPhase.CommitPhase, new DynamicShowcaseParticipant("Step-B"), 3, stepGroup = 2)
    ), Map("Step-A" -> DynamicShowcaseParticipant.Success, "Step-B" -> DynamicShowcaseParticipant.Success)),

    "retry" -> Scenario("retry", "Retryable Failure", List(
      SagaTransactionStep[Any, String, Any]("Step-A", SagaPhase.PreparePhase, new DynamicShowcaseParticipant("Step-A"), 3, stepGroup = 1),
      SagaTransactionStep[Any, String, Any]("Step-B", SagaPhase.PreparePhase, new DynamicShowcaseParticipant("Step-B"), 3, stepGroup = 2),
      SagaTransactionStep[Any, String, Any]("Step-A", SagaPhase.CommitPhase, new DynamicShowcaseParticipant("Step-A"), 3, stepGroup = 1),
      SagaTransactionStep[Any, String, Any]("Step-B", SagaPhase.CommitPhase, new DynamicShowcaseParticipant("Step-B"), 3, stepGroup = 2)
    ), Map("Step-A" -> DynamicShowcaseParticipant.Success, "Step-B" -> DynamicShowcaseParticipant.FailTwiceThenSucceed)),

    "compensation" -> Scenario("compensation", "Compensation (Prepare Failure)", List(
      SagaTransactionStep[Any, String, Any]("Step-A", SagaPhase.PreparePhase, new DynamicShowcaseParticipant("Step-A"), 3, stepGroup = 1),
      SagaTransactionStep[Any, String, Any]("Step-B", SagaPhase.PreparePhase, new DynamicShowcaseParticipant("Step-B"), 3, stepGroup = 2),
      SagaTransactionStep[Any, String, Any]("Step-A", SagaPhase.CompensatePhase, new DynamicShowcaseParticipant("Step-A"), 3, stepGroup = 1),
      SagaTransactionStep[Any, String, Any]("Step-B", SagaPhase.CompensatePhase, new DynamicShowcaseParticipant("Step-B"), 3, stepGroup = 2)
    ), Map("Step-A" -> DynamicShowcaseParticipant.Success, "Step-B" -> DynamicShowcaseParticipant.FailNonRetryable)),

    "suspended" -> Scenario("suspended", "Suspended (Compensation Failure)", List(
      SagaTransactionStep[Any, String, Any]("Step-A", SagaPhase.PreparePhase, new DynamicShowcaseParticipant("Step-A"), 3, stepGroup = 1),
      SagaTransactionStep[Any, String, Any]("Step-B", SagaPhase.PreparePhase, new DynamicShowcaseParticipant("Step-B"), 3, stepGroup = 2),
      SagaTransactionStep[Any, String, Any]("Step-A", SagaPhase.CompensatePhase, new DynamicShowcaseParticipant("Step-A"), 3, stepGroup = 1),
      SagaTransactionStep[Any, String, Any]("Step-B", SagaPhase.CompensatePhase, new DynamicShowcaseParticipant("Step-B"), 3, stepGroup = 2)
    ), Map("Step-A" -> DynamicShowcaseParticipant.FailNonRetryable, "Step-B" -> DynamicShowcaseParticipant.FailNonRetryable)),

    "commit-failure" -> Scenario("commit-failure", "Commit Phase Failure", List(
      SagaTransactionStep[Any, String, Any]("Step-A", SagaPhase.PreparePhase, new DynamicShowcaseParticipant("Step-A"), 3, stepGroup = 1),
      SagaTransactionStep[Any, String, Any]("Step-B", SagaPhase.PreparePhase, new DynamicShowcaseParticipant("Step-B"), 3, stepGroup = 2),
      SagaTransactionStep[Any, String, Any]("Step-A", SagaPhase.CommitPhase, new DynamicShowcaseParticipant("Step-A"), 3, stepGroup = 1),
      SagaTransactionStep[Any, String, Any]("Step-B", SagaPhase.CommitPhase, new DynamicShowcaseParticipant("Step-B"), 3, stepGroup = 2)
    ), Map("Step-A" -> DynamicShowcaseParticipant.Success, "Step-B" -> DynamicShowcaseParticipant.FailInCommit)),

    "partial-group" -> Scenario("partial-group", "Partial Group Failure", List(
      SagaTransactionStep[Any, String, Any]("Step-A", SagaPhase.PreparePhase, new DynamicShowcaseParticipant("Step-A"), 3, stepGroup = 1),
      SagaTransactionStep[Any, String, Any]("Step-B", SagaPhase.PreparePhase, new DynamicShowcaseParticipant("Step-B"), 3, stepGroup = 2),
      SagaTransactionStep[Any, String, Any]("Step-C", SagaPhase.PreparePhase, new DynamicShowcaseParticipant("Step-C"), 3, stepGroup = 2),
      SagaTransactionStep[Any, String, Any]("Step-A", SagaPhase.CompensatePhase, new DynamicShowcaseParticipant("Step-A"), 3, stepGroup = 1),
      SagaTransactionStep[Any, String, Any]("Step-B", SagaPhase.CompensatePhase, new DynamicShowcaseParticipant("Step-B"), 3, stepGroup = 2),
      SagaTransactionStep[Any, String, Any]("Step-C", SagaPhase.CompensatePhase, new DynamicShowcaseParticipant("Step-C"), 3, stepGroup = 2)
    ), Map("Step-A" -> DynamicShowcaseParticipant.Success, "Step-B" -> DynamicShowcaseParticipant.Success, "Step-C" -> DynamicShowcaseParticipant.FailNonRetryable)),

    "timeout" -> Scenario("timeout", "Global Timeout", List(
      SagaTransactionStep[Any, String, Any]("Step-A", SagaPhase.PreparePhase, new DynamicShowcaseParticipant("Step-A"), 3, stepGroup = 1),
      SagaTransactionStep[Any, String, Any]("Step-B", SagaPhase.PreparePhase, new DynamicShowcaseParticipant("Step-B"), 3, stepGroup = 2),
      SagaTransactionStep[Any, String, Any]("Step-A", SagaPhase.CommitPhase, new DynamicShowcaseParticipant("Step-A"), 3, stepGroup = 1),
      SagaTransactionStep[Any, String, Any]("Step-B", SagaPhase.CommitPhase, new DynamicShowcaseParticipant("Step-B"), 3, stepGroup = 2),
      SagaTransactionStep[Any, String, Any]("Step-A", SagaPhase.CompensatePhase, new DynamicShowcaseParticipant("Step-A"), 3, stepGroup = 1),
      SagaTransactionStep[Any, String, Any]("Step-B", SagaPhase.CompensatePhase, new DynamicShowcaseParticipant("Step-B"), 3, stepGroup = 2)
    ), Map("Step-A" -> DynamicShowcaseParticipant.Success, "Step-B" -> DynamicShowcaseParticipant.Timeout))
  )

  def injectFault(stepId: String, behavior: String) = Action {
    val b = behavior.toLowerCase match {
      case "success" => DynamicShowcaseParticipant.Success
      case "failretryable" => DynamicShowcaseParticipant.FailRetryable
      case "failnonretryable" => DynamicShowcaseParticipant.FailNonRetryable
      case "timeout" => DynamicShowcaseParticipant.Timeout
      case "failtwicethensucceed" => DynamicShowcaseParticipant.FailTwiceThenSucceed
      case _ => DynamicShowcaseParticipant.Success
    }
    DynamicShowcaseParticipant.setBehavior(stepId, b)
    Ok(Json.obj("status" -> "ok", "stepId" -> stepId, "behavior" -> behavior))
  }

  def triggerScenario(scenarioId: String, singleStep: Boolean) = Action {
    val sharding = ClusterSharding(typedSystem)
    val transactionId = java.util.UUID.randomUUID().toString
    val traceId = s"TRACE-${transactionId.substring(0, 8)}"
    
    val scenario = scenarios.getOrElse(scenarioId, Scenario("custom", "Custom", Nil, Map.empty))
    
    // Apply scenario behaviors
    scenario.behaviors.foreach { case (sid, b) => DynamicShowcaseParticipant.setBehavior(sid, b) }
    
    val steps: List[SagaTransactionStep[Any, String, Any]] = if (scenario.id == "custom") {
       // Default steps for custom mode
       List(
         SagaTransactionStep[Any, String, Any]("Step-A", SagaPhase.PreparePhase, new DynamicShowcaseParticipant("Step-A"), 3, stepGroup = 1),
         SagaTransactionStep[Any, String, Any]("Step-B", SagaPhase.PreparePhase, new DynamicShowcaseParticipant("Step-B"), 3, stepGroup = 2),
         SagaTransactionStep[Any, String, Any]("Step-C", SagaPhase.PreparePhase, new DynamicShowcaseParticipant("Step-C"), 3, stepGroup = 2),
         SagaTransactionStep[Any, String, Any]("Step-A", SagaPhase.CommitPhase, new DynamicShowcaseParticipant("Step-A"), 3, stepGroup = 1),
         SagaTransactionStep[Any, String, Any]("Step-B", SagaPhase.CommitPhase, new DynamicShowcaseParticipant("Step-B"), 3, stepGroup = 2),
         SagaTransactionStep[Any, String, Any]("Step-C", SagaPhase.CommitPhase, new DynamicShowcaseParticipant("Step-C"), 3, stepGroup = 2),
         SagaTransactionStep[Any, String, Any]("Step-A", SagaPhase.CompensatePhase, new DynamicShowcaseParticipant("Step-A"), 3, stepGroup = 1),
         SagaTransactionStep[Any, String, Any]("Step-B", SagaPhase.CompensatePhase, new DynamicShowcaseParticipant("Step-B"), 3, stepGroup = 2),
         SagaTransactionStep[Any, String, Any]("Step-C", SagaPhase.CompensatePhase, new DynamicShowcaseParticipant("Step-C"), 3, stepGroup = 2)
       )
    } else {
       scenario.steps
    }

    import net.imadz.application.services.MoneyTransferService
    import scala.concurrent.duration._
    val timeout = if (scenarioId == "timeout") Some(15.seconds) else None
    sharding.entityRefFor(MoneyTransferService.moneyTransferCoordinatorKey, transactionId) ! SagaTransactionCoordinator.StartTransaction(transactionId, steps, None, traceId, singleStep, timeout)
    Ok(Json.obj("status" -> "ok", "transactionId" -> transactionId, "traceId" -> traceId, "scenario" -> scenario.name))
  }

  def triggerShowcase(singleStep: Boolean) = Action {
    Redirect(routes.ShowcaseController.triggerScenario("custom", singleStep))
  }

  def socket = WebSocket.accept[String, String] { request =>
    akka.stream.scaladsl.Flow.fromSinkAndSource(Sink.ignore, hubSource.map(e => Json.toJson(e)(sagaProgressEventWrites).toString()))
  }

  def proceed(transactionId: String) = Action {
    import net.imadz.application.services.MoneyTransferService
    ClusterSharding(typedSystem).entityRefFor(MoneyTransferService.moneyTransferCoordinatorKey, transactionId) ! net.imadz.infra.saga.SagaTransactionCoordinator.ProceedNext(None)
    Ok(Json.obj("status" -> "ok", "transactionId" -> transactionId))
  }

  def fixStep(transactionId: String, stepId: String, phase: String) = Action {
    import net.imadz.infra.saga.SagaPhase
    import net.imadz.application.services.MoneyTransferService
    val p = phase.toLowerCase match { case "prepare" => SagaPhase.PreparePhase; case "commit" => SagaPhase.CommitPhase; case "compensate" => SagaPhase.CompensatePhase; case _ => SagaPhase.PreparePhase }
    ClusterSharding(typedSystem).entityRefFor(MoneyTransferService.moneyTransferCoordinatorKey, transactionId) ! net.imadz.infra.saga.SagaTransactionCoordinator.ManualFixStep(stepId, p, None)
    Ok(Json.obj("status" -> "ok", "transactionId" -> transactionId))
  }

  def resume(transactionId: String) = Action {
    import net.imadz.application.services.MoneyTransferService
    ClusterSharding(typedSystem).entityRefFor(MoneyTransferService.moneyTransferCoordinatorKey, transactionId) ! net.imadz.infra.saga.SagaTransactionCoordinator.ResolveSuspended(None)
    Ok(Json.obj("status" -> "ok", "transactionId" -> transactionId))
  }

  def retryPhase(transactionId: String) = Action {
    import net.imadz.application.services.MoneyTransferService
    ClusterSharding(typedSystem).entityRefFor(MoneyTransferService.moneyTransferCoordinatorKey, transactionId) ! net.imadz.infra.saga.SagaTransactionCoordinator.RetryCurrentPhase(None)
    Ok(Json.obj("status" -> "ok", "transactionId" -> transactionId))
  }

  // --- Historical Replay API ---
  def getHistory(transactionId: String) = Action.async {
    import net.imadz.infra.saga.SagaTransactionCoordinator
    import net.imadz.infra.saga.persistence.{SagaTransactionCoordinatorEventAdapter, StepExecutorEventAdapter}
    import akka.actor.ExtendedActorSystem

    val coordAdapter = new SagaTransactionCoordinatorEventAdapter(system.asInstanceOf[ExtendedActorSystem])
    val stepAdapter = new StepExecutorEventAdapter(system.asInstanceOf[ExtendedActorSystem])
    val coordinatorId = s"Saga-MoneyTransfer|$transactionId"

    readJournal.currentEventsByPersistenceId(coordinatorId, 0, Long.MaxValue).runWith(Sink.seq).flatMap { coordEnvelopes =>
      val coordEventsWithTs = coordEnvelopes.flatMap { env =>
        val evt = env.event match {
          case po: net.imadz.infra.saga.proto.saga_v2.SagaTransactionCoordinatorEventPO => coordAdapter.fromJournal(po, "").events.headOption
          case other => Some(other.asInstanceOf[SagaTransactionCoordinator.Event])
        }
        evt.map(e => (env.timestamp, e))
      }

      val stepsDef = coordEventsWithTs.map(_._2).collectFirst { case e: SagaTransactionCoordinator.TransactionStarted => e.steps }.getOrElse(Nil)
      
      val queryTasks = for {
        s <- stepsDef
        p <- List(net.imadz.infra.saga.SagaPhase.PreparePhase, net.imadz.infra.saga.SagaPhase.CommitPhase, net.imadz.infra.saga.SagaPhase.CompensatePhase)
        suffix <- List(p.toString, p.toString.toLowerCase.replace("phase", ""))
      } yield (s"$transactionId-${s.stepId}-$suffix", s.stepId, p.toString.toLowerCase.replace("phase", ""))

      Future.sequence(queryTasks.distinct.map { case (pid, sid, ph) =>
        readJournal.currentEventsByPersistenceId(pid, 0, Long.MaxValue).map { env =>
          val evt = env.event match {
            case po: net.imadz.infra.saga.proto.saga_v2.StepExecutorEventPO => stepAdapter.fromJournal(po, "").events.headOption
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

  private def buildHistory(txId: String, coordWithTs: Seq[(Long, net.imadz.infra.saga.SagaTransactionCoordinator.Event)], stepEvts: Seq[(Long, String, String, net.imadz.infra.saga.StepExecutor.Event)]): Seq[JsValue] = {
    import net.imadz.infra.saga.SagaTransactionCoordinator._
    import net.imadz.infra.saga.StepExecutor._

    val coordEnvelopes = coordWithTs.map { case (ts, evt) =>
      val pEvt = evt match {
        case e: TransactionStarted =>
          val stepsInfo = e.steps.map(s => SagaProgressEvent.StepInfo(s.stepId, s.stepGroup)).distinct
          SagaProgressEvent.TransactionStarted(e.transactionId, stepsInfo, e.traceId)
        case e: TransactionCompleted => SagaProgressEvent.TransactionCompleted(e.transactionId, "")
        case e: TransactionFailed => SagaProgressEvent.TransactionFailed(e.transactionId, e.reason, "")
        case e: TransactionSuspended => SagaProgressEvent.TransactionSuspended(e.transactionId, e.reason, "")
        case _ => null
      }
      (ts, pEvt)
    }

    val stepEnvelopes = stepEvts.map { case (ts, sid, ph, evt) =>
      val pEvt = evt match {
        case ex: ExecutionStarted[_, _, _] => SagaProgressEvent.StepOngoing(txId, sid, ph, ex.traceId)
        case ex: OperationSucceeded[_] => SagaProgressEvent.StepCompleted(txId, sid, ph, ex.traceId, isManual = false)
        case ex: ManualFixCompleted[_] => SagaProgressEvent.StepCompleted(txId, sid, ph, ex.traceId, isManual = true)
        case ex: OperationFailed => SagaProgressEvent.StepFailed(txId, sid, ph, ex.error.message, ex.traceId)
        case ex: RetryScheduled => SagaProgressEvent.StepFailed(txId, sid, ph, s"Retry #${ex.retryCount}", ex.traceId)
        case _ => null
      }
      (ts, pEvt)
    }

    (coordEnvelopes ++ stepEnvelopes).filter(_._2 != null).sortBy { case (ts, evt) =>
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
