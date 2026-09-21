package net.imadz.common.auth

import pdi.jwt.{JwtAlgorithm, JwtJson}
import play.api.Configuration
import play.api.libs.json.Json

import java.time.Instant
import javax.inject.{Inject, Singleton}

/** 认证配置（Authentication_Design.md §一）。secret 生产由 env AUTH_SECRET 注入 */
case class AuthConfig(secret: String, accessTtlSeconds: Int, refreshTtlSeconds: Int)

@Singleton
class AuthConfiguration @Inject() (conf: Configuration) extends ConfigurationLike {
  override val secret: String = conf.get[String]("auth.secret")
  override val accessTtlSeconds: Int = conf.get[Int]("auth.accessTtlSeconds")
  override val refreshTtlSeconds: Int = conf.get[Int]("auth.refreshTtlSeconds")
}

trait ConfigurationLike {
  def secret: String
  def accessTtlSeconds: Int
  def refreshTtlSeconds: Int
}

/** 租户上下文（EP_S2_9_Session_Tenant_Claims D5-A：登录时经名册验证后烧入 token）。
  * v1 仅携带 tenantId——读模型暂无独立成员实体 ID / 角色列，不造值（A3）。 */
case class TenantIdentity(tenantId: String, role: String)

/** JWT 签发与本地验签（P1 本地验签；HS256 v1，RS256 预留） */
@Singleton
class TokenService @Inject() (config: ConfigurationLike) {
  private val secret = config.secret
  private val alg = JwtAlgorithm.HS256

  /** 2026-08-27：暴露真实 TTL（authResponse 的 expiresIn 展示值与实际 exp 同源） */
  def accessTtlSeconds: Int = config.accessTtlSeconds

  /** 签发 access token：(token, expiresAtEpochSec)。tenant=None 时与旧协议字节兼容（纯 uid/tv/exp） */
  def issueAccessToken(userId: String, tokenVersion: Int, tenant: Option[TenantIdentity] = None): (String, Long) = {
    val exp = Instant.now().getEpochSecond + config.accessTtlSeconds
    val base = Json.obj("uid" -> userId, "tv" -> tokenVersion, "exp" -> exp)
    val claim = tenant.map { t =>
      (base ++ Json.obj("tid" -> t.tenantId)) ++
        (if (t.role.nonEmpty) Json.obj("mrole" -> t.role) else Json.obj())
    }.getOrElse(base)
    (JwtJson.encode(claim, secret, alg), exp)
  }

  /** 本地验签：签名/exp/算法白名单（pdi jwt 默认拒绝 alg 混淆）。失败返回 None。 */
  def verifyAccess(token: String): Option[VerifiedToken] =
    JwtJson.decodeJson(token, secret, Seq(alg)).toOption.flatMap { claim =>
      for {
        uid <- (claim \ "uid").asOpt[String]
        tv <- (claim \ "tv").asOpt[Int]
        exp <- (claim \ "exp").asOpt[Long]
        // D5-A：有 tid 即携带租户上下文；mrole 缺省空串（视图无角色列，A3 不造值）
        tenant = (claim \ "tid").asOpt[String]
          .map(tid => TenantIdentity(tid, (claim \ "mrole").asOpt[String].getOrElse("")))
      } yield VerifiedToken(uid, tv, exp, tenant)
    }
}

case class VerifiedToken(userId: String, tokenVersion: Int, expiresAtEpochSec: Long,
                         tenant: Option[TenantIdentity] = None)
