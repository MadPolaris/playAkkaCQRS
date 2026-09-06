package controllers

import javax.inject.{Inject, Singleton}
import play.api.mvc._
import play.api.libs.json.Json
import play.api.libs.json.{JsObject, JsString, Json}
import akka.actor.ActorSystem
import akka.stream.Materializer
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext
import net.imadz.m25.bank.BankBatchDemoService
import net.imadz.m25.bank.BankBatchJson

/** 银行批量充值+申购演示（Monarch 六阶段链，规模版）——页面 + WebSocket + API。 */
@Singleton
class BankBatchController @Inject()(
    cc: ControllerComponents,
    bankBatchDemoService: BankBatchDemoService,
    implicit val system: ActorSystem,
    implicit val mat: Materializer,
    implicit val ec: ExecutionContext
) extends AbstractController(cc) {

  /** 首次访问即完成分片注册（幂等）；也可由 ApplicationBootstrap 调 initSharding。 */
  private def ensureInit(): Unit = bankBatchDemoService.initSharding()

  def page: Action[AnyContent] = Action { implicit request =>
    ensureInit()
    Ok(views.html.bankBatchDemo())
  }

  def start: Action[AnyContent] = Action {
    ensureInit()
    bankBatchDemoService.startRun()
    Ok(bankBatchDemoService.statsJson)
  }

  def crash: Action[AnyContent] = Action {
    val result = bankBatchDemoService.crashRandom()
    Ok(Json.stringify(JsObject(result.map { case (k, v) => k -> JsString(v.toString) })))
  }

  def state: Action[AnyContent] = Action {
    Ok(bankBatchDemoService.statsJson)
  }

  def exceptions: Action[AnyContent] = Action {
    import net.imadz.m25.bank.BankBatchDemoService
    val rows = bankBatchDemoService.exceptionList.map { e =>
      Json.obj("chain" -> e.chain, "customerId" -> e.customerId, "name" -> e.name,
        "amount" -> e.amount, "reason" -> e.reason,
        "at" -> java.time.Instant.ofEpochMilli(e.at).toString)
    }
    Ok(Json.obj("count" -> rows.size, "entries" -> rows))
  }

  def replayAll: Action[AnyContent] = Action.async {
    bankBatchDemoService.replayAllExceptions().map(n => Ok(Json.obj("replayed" -> n)))
  }

  def replayOne(chain: String, customerId: String): Action[AnyContent] = Action.async {
    bankBatchDemoService.replayException(chain, customerId).map {
      case true  => Ok(Json.obj("replayed" -> true))
      case false => NotFound(Json.obj("error" -> "not in exception pool"))
    }
  }

  def events: WebSocket = WebSocket.accept[String, String] { _ =>
    akka.stream.scaladsl.Flow.fromSinkAndSource(akka.stream.scaladsl.Sink.ignore, bankBatchDemoService.source)
  }
}
