package controllers

import akka.actor.typed.ActorSystem
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.adapter._
import akka.stream.Materializer
import akka.stream.scaladsl.{BroadcastHub, Keep, MergeHub, Sink, Source}
import net.imadz.application.services.transactor.DynamicShowcaseParticipant
import net.imadz.infra.saga.SagaProgressEvent
import play.api.libs.json.{JsValue, Json, Writes}
import play.api.mvc._

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class ShowcaseController @Inject()(val controllerComponents: ControllerComponents,
                                   implicit val system: akka.actor.ActorSystem,
                                   implicit val mat: Materializer,
                                   implicit val ec: ExecutionContext) extends BaseController {

  implicit val typedSystem: ActorSystem[Nothing] = system.toTyped

  // JSON Writes for SagaProgressEvent
  implicit val transactionStartedWrites: Writes[SagaProgressEvent.TransactionStarted] = Json.writes[SagaProgressEvent.TransactionStarted]
  implicit val stepOngoingWrites: Writes[SagaProgressEvent.StepOngoing] = Json.writes[SagaProgressEvent.StepOngoing]
  implicit val stepCompletedWrites: Writes[SagaProgressEvent.StepCompleted] = Json.writes[SagaProgressEvent.StepCompleted]
  implicit val stepFailedWrites: Writes[SagaProgressEvent.StepFailed] = Json.writes[SagaProgressEvent.StepFailed]
  implicit val transactionCompletedWrites: Writes[SagaProgressEvent.TransactionCompleted] = Json.writes[SagaProgressEvent.TransactionCompleted]
  implicit val transactionFailedWrites: Writes[SagaProgressEvent.TransactionFailed] = Json.writes[SagaProgressEvent.TransactionFailed]
  implicit val transactionSuspendedWrites: Writes[SagaProgressEvent.TransactionSuspended] = Json.writes[SagaProgressEvent.TransactionSuspended]

  implicit val sagaProgressEventWrites: Writes[SagaProgressEvent] = Writes {
    case e: SagaProgressEvent.TransactionStarted => Json.obj("type" -> "TransactionStarted", "data" -> Json.toJson(e))
    case e: SagaProgressEvent.StepOngoing => Json.obj("type" -> "StepOngoing", "data" -> Json.toJson(e))
    case e: SagaProgressEvent.StepCompleted => Json.obj("type" -> "StepCompleted", "data" -> Json.toJson(e))
    case e: SagaProgressEvent.StepFailed => Json.obj("type" -> "StepFailed", "data" -> Json.toJson(e))
    case e: SagaProgressEvent.TransactionCompleted => Json.obj("type" -> "TransactionCompleted", "data" -> Json.toJson(e))
    case e: SagaProgressEvent.TransactionFailed => Json.obj("type" -> "TransactionFailed", "data" -> Json.toJson(e))
    case e: SagaProgressEvent.TransactionSuspended => Json.obj("type" -> "TransactionSuspended", "data" -> Json.toJson(e))
  }

  // Create a Source from Akka EventStream
  private val (hubSink, hubSource) = MergeHub.source[SagaProgressEvent]
    .toMat(BroadcastHub.sink[SagaProgressEvent])(Keep.both)
    .run()

  // Bridging Actor to subscribe to EventStream and push to Source
  private val bridgeActor = system.actorOf(akka.actor.Props(new akka.actor.Actor {
    override def preStart(): Unit = {
      system.eventStream.subscribe(self, classOf[SagaProgressEvent])
    }
    def receive: Receive = {
      case e: SagaProgressEvent => Source.single(e).runWith(hubSink)
    }
  }))

  def index() = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.showcase())
  }

  def socket = WebSocket.accept[String, String] { request =>
    akka.stream.scaladsl.Flow.fromSinkAndSource(
      Sink.ignore,
      hubSource.map(e => Json.toJson(e).toString())
    )
  }

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

  def triggerShowcase(singleStep: Boolean) = Action {
    import akka.cluster.sharding.typed.scaladsl.ClusterSharding
    import net.imadz.infra.saga.{SagaTransactionStep, SagaPhase}
    import net.imadz.infra.saga.SagaTransactionCoordinator.StartTransaction

    val sharding = ClusterSharding(typedSystem)
    val transactionId = java.util.UUID.randomUUID().toString
    val traceId = s"TRACE-${transactionId.substring(0, 8)}"
    
    val steps = List(
      // Prepare Phase
      SagaTransactionStep("Step-A", SagaPhase.PreparePhase, new DynamicShowcaseParticipant("Step-A"), 3, traceId = traceId, stepGroup = 1),
      SagaTransactionStep("Step-B", SagaPhase.PreparePhase, new DynamicShowcaseParticipant("Step-B"), 3, traceId = traceId, stepGroup = 2),
      SagaTransactionStep("Step-C", SagaPhase.PreparePhase, new DynamicShowcaseParticipant("Step-C"), 3, traceId = traceId, stepGroup = 2),
      
      // Commit Phase
      SagaTransactionStep("Step-A", SagaPhase.CommitPhase, new DynamicShowcaseParticipant("Step-A"), 3, traceId = traceId, stepGroup = 1),
      SagaTransactionStep("Step-B", SagaPhase.CommitPhase, new DynamicShowcaseParticipant("Step-B"), 3, traceId = traceId, stepGroup = 2),
      SagaTransactionStep("Step-C", SagaPhase.CommitPhase, new DynamicShowcaseParticipant("Step-C"), 3, traceId = traceId, stepGroup = 2),
      
      // Compensate Phase
      SagaTransactionStep("Step-A", SagaPhase.CompensatePhase, new DynamicShowcaseParticipant("Step-A"), 3, traceId = traceId, stepGroup = 1),
      SagaTransactionStep("Step-B", SagaPhase.CompensatePhase, new DynamicShowcaseParticipant("Step-B"), 3, traceId = traceId, stepGroup = 2),
      SagaTransactionStep("Step-C", SagaPhase.CompensatePhase, new DynamicShowcaseParticipant("Step-C"), 3, traceId = traceId, stepGroup = 2)
    )

    import net.imadz.application.services.MoneyTransferService
    val coordinatorRef = sharding.entityRefFor(MoneyTransferService.moneyTransferCoordinatorKey, transactionId)
    
    coordinatorRef ! StartTransaction(transactionId, steps, None, traceId, singleStep)
    
    Ok(Json.obj("status" -> "ok", "transactionId" -> transactionId, "traceId" -> traceId))
  }

  def proceed(transactionId: String) = Action {
    import akka.cluster.sharding.typed.scaladsl.ClusterSharding
    import net.imadz.infra.saga.SagaTransactionCoordinator.ProceedNext

    val sharding = ClusterSharding(typedSystem)
    import net.imadz.application.services.MoneyTransferService
    val coordinatorRef = sharding.entityRefFor(MoneyTransferService.moneyTransferCoordinatorKey, transactionId)
    
    coordinatorRef ! ProceedNext(None)
    
    Ok(Json.obj("status" -> "ok", "transactionId" -> transactionId))
  }

  def fixStep(transactionId: String, stepId: String, phase: String) = Action {
    import akka.cluster.sharding.typed.scaladsl.ClusterSharding
    import net.imadz.infra.saga.SagaTransactionCoordinator.ManualFixStep
    import net.imadz.infra.saga.SagaPhase

    val sharding = ClusterSharding(typedSystem)
    import net.imadz.application.services.MoneyTransferService
    val coordinatorRef = sharding.entityRefFor(MoneyTransferService.moneyTransferCoordinatorKey, transactionId)

    val p = phase.toLowerCase match {
      case "prepare" => SagaPhase.PreparePhase
      case "commit" => SagaPhase.CommitPhase
      case "compensate" => SagaPhase.CompensatePhase
      case _ => SagaPhase.PreparePhase
    }

    coordinatorRef ! ManualFixStep(stepId, p, None)

    Ok(Json.obj("status" -> "ok", "transactionId" -> transactionId, "stepId" -> stepId, "phase" -> phase))
  }

  def resume(transactionId: String) = Action {
    import akka.cluster.sharding.typed.scaladsl.ClusterSharding
    import net.imadz.infra.saga.SagaTransactionCoordinator.ResolveSuspended

    val sharding = ClusterSharding(typedSystem)
    import net.imadz.application.services.MoneyTransferService
    val coordinatorRef = sharding.entityRefFor(MoneyTransferService.moneyTransferCoordinatorKey, transactionId)

    coordinatorRef ! ResolveSuspended(None)

    Ok(Json.obj("status" -> "ok", "transactionId" -> transactionId))
  }

  def retryPhase(transactionId: String) = Action {
    import akka.cluster.sharding.typed.scaladsl.ClusterSharding
    import net.imadz.infra.saga.SagaTransactionCoordinator.RetryCurrentPhase

    val sharding = ClusterSharding(typedSystem)
    import net.imadz.application.services.MoneyTransferService
    val coordinatorRef = sharding.entityRefFor(MoneyTransferService.moneyTransferCoordinatorKey, transactionId)

    coordinatorRef ! RetryCurrentPhase(None)

    Ok(Json.obj("status" -> "ok", "transactionId" -> transactionId))
  }
}
