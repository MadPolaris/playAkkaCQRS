package net.imadz.common.auth

import play.api.libs.json._

/** ============================================================
 * 边界 Codec（用户实证痛点正解）：String ⇄ 强类型 Id 只在此翻译一次。
 * 一个 implicit 到位：path/query 参数解析失败=400 PARAM_INVALID（不是 .get 异常）、
 * body 字段 Reads[I] / 响应 Writes[I] 自动派生——改字段类型不再全站陪葬。
 * 标杆：declarative_controller_dsl/IdCodec.scala
 * ============================================================ */
trait IdCodec[I] {
  def parse(raw: String): Either[String, I]
  def render(id: I): String
}

object IdCodec {
  def apply[I](implicit c: IdCodec[I]): IdCodec[I] = c

  /** 简单包裹型 Id 的通用构造器（绝大多数领域 ID 形状） */
  def wrapped[I](f: String => I)(g: I => String): IdCodec[I] = new IdCodec[I] {
    def parse(raw: String): Either[String, I] =
      if (raw == null || raw.trim.isEmpty) Left("empty id") else Right(f(raw))
    def render(id: I): String = g(id)
  }

  /** play-json 自动派生：body 解码与响应写出共用同一翻译 */
  implicit def jsonReads[I](implicit c: IdCodec[I]): Reads[I] = new Reads[I] {
    def reads(j: JsValue) = j.validate[String].fold(
      invalid => JsError(invalid),
      raw => c.parse(raw).fold(JsError(_), JsSuccess(_))
    )
  }
  implicit def jsonWrites[I](implicit c: IdCodec[I]): Writes[I] = (i: I) => JsString(c.render(i))
}
