package net.imadz.common.auth

import play.api.libs.json.Json
import play.api.mvc.Results._
import play.api.mvc._

import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

/** TenantSession——租户会话请求上下文（D5-A 拍板命名，Authentication_Design.md §八/§九）。
  * userId：全局身份；tenant：登录时经名册验证烧入 token 的租户上下文（旧协议 token 为 None）。
  * TenantId Propagation 契约的起点：下游 controller → command/event → 投影 → 图 全链以此 tenantId 为准。
  * 兼容别名 AuthUserRequest 保留至 gen_api_layer 模板切换（见文件尾）。 */
case class TenantSession[A](userId: String, request: Request[A],
                            tenant: Option[TenantIdentity] = None) extends WrappedRequest(request) {

  /** 传播用快捷取值：无租户会话时为 None，由 TenantRequiredAction 上游把关 */
  def tenantId: Option[String] = tenant.map(_.tenantId)
}

/** 兼容别名见同目录 package.scala（AuthUserRequest → TenantSession），gen_api_layer 模板切换后移除 */

/**
 * 认证 ActionBuilder（Authentication_Design.md P1/P2/P3/P5）：
 * 本地验签 → 401 语义（INVALID_TOKEN/TOKEN_EXPIRED）→ TenantSession 继续。
 * tokenVersion 吊销检查在 B2 接入（UserEntity 缓存查询）。
 */
@Singleton
class AuthAction @Inject() (parser: BodyParsers.Default, tokens: TokenService)(implicit ec: ExecutionContext)
  extends ActionBuilder[TenantSession, AnyContent] {

  override def parser: BodyParser[AnyContent] = parser
  override protected def executionContext: ExecutionContext = ec

  override def invokeBlock[A](request: Request[A], block: TenantSession[A] => Future[Result]): Future[Result] = {
    val tokenOpt = request.headers.get("Authorization")
      .filter(_.startsWith("Bearer "))
      .map(_.drop(7).trim)

    tokenOpt match {
      case None =>
        Future.successful(unauthorized("INVALID_TOKEN", "missing or malformed Authorization header"))
      case Some(token) =>
        tokens.verifyAccess(token) match {
          case Some(vt) if vt.expiresAtEpochSec >= Instant.now().getEpochSecond - 30 =>
            block(TenantSession(vt.userId, request, vt.tenant))
          case Some(_) =>
            Future.successful(unauthorized("TOKEN_EXPIRED", "access token expired"))
          case None =>
            Future.successful(unauthorized("INVALID_TOKEN", "token verification failed"))
        }
    }
  }

  private def unauthorized(code: String, message: String): Result =
    Unauthorized(Json.obj("errorCode" -> code, "message" -> message))
}

/** 租户门卫（D5-A TenantId Propagation 第一环）：要求会话已携带租户上下文，
  * 否则 403 TENANT_REQUIRED。供工作台/租户域端点组合使用：
  * `authAction.tenantRequired { tsession: TenantSession[_] => ... }` 或
  * `authAction ~> TenantRequiredAction` 风格按需封装。 */
class TenantRequired @Inject() () extends ActionRefiner[TenantSession, TenantSession] {
  override protected def executionContext: ExecutionContext =
    scala.concurrent.ExecutionContext.Implicits.global

  override protected def refine[A](request: TenantSession[A]): Future[Either[Result, TenantSession[A]]] =
    Future.successful(
      request.tenant match {
        case Some(_) => Right(request)
        case None    => Left(Forbidden(Json.obj("errorCode" -> "TENANT_REQUIRED",
          "message" -> "session carries no tenant context; re-login to bind a tenant")))
      })
}
