package net.imadz.common.auth

import play.api.libs.json.{JsObject, JsValue, Json, OWrites, Writes}
import play.api.mvc.Results.Status
import play.api.mvc.Result

/** ============================================================
 * 错误形状单点（G5 收敛；EP_Declarative_Controller_DSL B1）。
 * 全站唯一 ApiError → Result 折叠规则；gen_api_layer 直出不再手写
 * Json.obj("errorCode"->...)。设计依据：declarative_auth_gap_assessment.md
 * 标杆镜像：backend-codegen knowledge_base/design_docs/declarative_controller_dsl/
 * B5-L L1（方案 a）：payload 键顶层合并——establish 409 的
 * existingConceptId 三键顶层逐字节保持；payload 缺省 None＝旧 wire 形状零漂移。
 * ============================================================ */
final case class ApiError(status: Int, errorCode: String, message: String, payload: Option[JsObject] = None)

object ApiError {
  def forbidden(code: String, msg: String): ApiError = ApiError(403, code, msg)
  def badRequest(code: String, msg: String): ApiError = ApiError(400, code, msg)
  def notFound(code: String, msg: String): ApiError = ApiError(404, code, msg)
  def conflict[P](code: String, msg: String, payload: P)(implicit pw: OWrites[P]): ApiError =
    ApiError(409, code, msg, Some(pw.writes(payload)))
  implicit val writes: Writes[ApiError] = e =>
    Json.obj("errorCode" -> e.errorCode, "message" -> e.message) ++ e.payload.getOrElse(Json.obj())

  /** 单点折叠：handler 一律返回 Either[ApiError, T]，DSL 内部统一翻译为 Result */
  def toResult[T](e: Either[ApiError, T])(implicit w: OWrites[T]): Result = e match {
    case Right(t) => Status(200)(w.writes(t))
    case Left(err) => Status(err.status)(Json.toJson(err))
  }

  /** Id/String 边界翻译失败 ⇒ 统一 400 PARAM_INVALID（.get 异常流的声明式替代） */
  def param[T](e: Either[String, T]): Either[ApiError, T] =
    e.left.map(msg => badRequest("PARAM_INVALID", s"unparseable identifier: $msg"))
}

/** JsValue 出口版折叠（dispatch 形态 D 专用：分支 VO 经 writes 后收敛 JsValue） */
object ApiRaw {
  def ok(js: JsValue): Result = Status(200)(js)
}
