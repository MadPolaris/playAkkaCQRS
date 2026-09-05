package controllers

import javax.inject.{Inject, Singleton}
import play.api.mvc._
import play.api.libs.json.{JsObject, JsString, Json}
import akka.actor.ActorSystem
import akka.stream.Materializer
import net.imadz.m25.bank.BankBatchDemoService
import net.imadz.m25.bank.BankBatchJson

/** 银行批量充值+申购演示（Monarch 六阶段链，规模版）——页面 + WebSocket + API。 */
@Singleton
class BankBatchController @Inject()(
    cc: ControllerComponents,
    bankBatchDemoService: BankBatchDemoService,
    implicit val system: ActorSystem,
    implicit val mat: Materializer
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

  def events: WebSocket = WebSocket.accept[String, String] { _ =>
    akka.stream.scaladsl.Flow.fromSinkAndSource(akka.stream.scaladsl.Sink.ignore, bankBatchDemoService.source)
  }
}
