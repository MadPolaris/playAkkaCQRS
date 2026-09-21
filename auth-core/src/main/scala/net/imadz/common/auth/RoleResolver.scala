package net.imadz.common.auth

import javax.inject.Inject
import play.api.libs.json.Reads

import scala.concurrent.Future

/** ============================================================
 * RoleResolver —— 多层角色解析注入点（roleList = authorize(scopeId, userId)）。
 * token 只带 tenant 层 mrole；深层 scope 角色在此查名册+归属一致性校验
 * （实体自带 tenantId，等值校验即可——B2b 战役红利）。
 *
 * ⚠️ B1 诚实边界：真仓名册当前**无角色列**（A3 待造，TokenService 注释在案），
 * 故唯一交付实现 = NotFoundRoleResolver（fail-closed：无数据源=非成员 403）+
 * CachedRoleResolver 装饰器。真实名册实现随角色列落地（B2/B5 批）——
 * 在 RoleGate.Migration 模式下 resolver=None 触发的是成员关系拒绝而非角色拒绝，
 * 接线 resolver 的端点应等待名册就绪（B2 模板默认不挂子域根）。
 * ============================================================ */
trait RoleResolver {
  def workgroupRole(workgroupId: String, userId: String, expectedTenantId: String): Future[Option[String]]
  def projectRole(projectId: String, userId: String, expectedTenantId: String): Future[Option[String]]
  def workshopRole(workshopId: String, userId: String, expectedTenantId: String): Future[Option[String]]
}

/** B1 缺省实现：无数据源 → 一律非成员（fail-closed）。供未接名册的部署档使用。
 *  WP-6 硬门修复（2026-08-30）：原 (implicit ec) 构造器无 @Inject 且 ec 从未被使用——
 *  Guice 无法实例化，B2 控制器注入 Endpoint（依赖本类）的任何服务启动即 19 键 CreationException
 *  （B2 控制器 08-28 才应用、9000 上一次启动 08-27，缺陷潜伏至今被硬门首次实测暴露）。
 *  改零参 @Inject 构造；Future.successful 不依赖 ec，语义零变化。 */
final class NotFoundRoleResolver @Inject() () extends RoleResolver {
  private val none = Future.successful(None)
  def workgroupRole(workgroupId: String, userId: String, expectedTenantId: String): Future[Option[String]] = none
  def projectRole(projectId: String, userId: String, expectedTenantId: String): Future[Option[String]] = none
  def workshopRole(workshopId: String, userId: String, expectedTenantId: String): Future[Option[String]] = none
}

/** 60s TTL 缓存装饰器（用户拍板形态：名册直查+缓存；evict 供成员变更事件挂勾） */
final class CachedRoleResolver(underlying: RoleResolver, val ttlSeconds: Int = 60)(implicit ec: scala.concurrent.ExecutionContext)
  extends RoleResolver {
  private case class Entry(role: Option[String], cachedAtSec: Long)
  @volatile private var wgCache = Map.empty[(String, String, String), Entry]
  @volatile private var projectCache = Map.empty[(String, String, String), Entry]
  @volatile private var workshopCache = Map.empty[(String, String, String), Entry]

  private def nowSec(): Long = System.currentTimeMillis() / 1000

  private def cachedGet(cache: Map[(String, String, String), Entry], key: (String, String, String)): Option[Option[String]] =
    cache.get(key).collect { case e if nowSec() - e.cachedAtSec < ttlSeconds => e.role }

  def workgroupRole(workgroupId: String, userId: String, expectedTenantId: String): Future[Option[String]] = {
    val key = (workgroupId, userId, expectedTenantId)
    cachedGet(wgCache, key) match {
      case Some(role) => Future.successful(role)
      case None => underlying.workgroupRole(workgroupId, userId, expectedTenantId).map { r =>
        synchronized { wgCache += (key -> Entry(r, nowSec())) }; r
      }
    }
  }
  def projectRole(projectId: String, userId: String, expectedTenantId: String): Future[Option[String]] = {
    val key = (projectId, userId, expectedTenantId)
    cachedGet(projectCache, key) match {
      case Some(role) => Future.successful(role)
      case None => underlying.projectRole(projectId, userId, expectedTenantId).map { r =>
        synchronized { projectCache += (key -> Entry(r, nowSec())) }; r
      }
    }
  }
  def workshopRole(workshopId: String, userId: String, expectedTenantId: String): Future[Option[String]] = {
    val key = (workshopId, userId, expectedTenantId)
    cachedGet(workshopCache, key) match {
      case Some(role) => Future.successful(role)
      case None => underlying.workshopRole(workshopId, userId, expectedTenantId).map { r =>
        synchronized { workshopCache += (key -> Entry(r, nowSec())) }; r
      }
    }
  }

  /** 成员变更时由 projection 主动调用（OrganizationMember/WorkgroupMember 事件挂勾） */
  def evict(scope: String, scopeId: String, userId: String): Unit = synchronized {
    scope.toLowerCase match {
      case "workgroup" => wgCache = wgCache.filterNot { case ((w, u, _), _) => w == scopeId && u == userId }
      case "project"   => projectCache = projectCache.filterNot { case ((p, u, _), _) => p == scopeId && u == userId }
      case "workshop"  => workshopCache = workshopCache.filterNot { case ((w, u, _), _) => w == scopeId && u == userId }
      case _ => ()
    }
  }
}
