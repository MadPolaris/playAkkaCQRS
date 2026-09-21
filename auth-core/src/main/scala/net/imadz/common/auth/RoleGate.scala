package net.imadz.common.auth

import play.api.Logger

/** ============================================================
 * 角色词表（用户拍板 2026-08-28：13 值五层，授予层自明；S2-87 2026-09-13 补 Session 层
 * Observer＝14 值·Session 协作面六值白名单见 Roles.SessionRoles）+ RoleGate。
 *
 * 层 ↔ 对照点：Global=globalRole(登录恒有)｜Tenant=token mrole｜
 * Workgroup/Project/Workshop=RoleResolver 名册。
 * 实体模型：XXX Member 无实体仅角色（Id=orgMemberId）；Session follows Workshop。
 * ⚠️ GENERATED 值域：与 notes.role: 真源对账（Altair validate_rolelink_tiers.mjs，
 * 白名单两层=租户层 11+全局层 2）。
 * ============================================================ */
object Roles {
  // ---- Global 层（globalRole 登录恒有；Platform 运维归此） ----
  val User: String = "User"
  val SystemOperator: String = "SystemOperator"

  // ---- Tenant 层（token mrole 值域） ----
  val TenantOwner: String = "TenantOwner"
  val TenantMember: String = "TenantMember"
  val System: String = "System"
  val AiAgent: String = "AI Agent"

  // ---- Workgroup 层 ----
  val WorkgroupOwner: String = "WorkgroupOwner"
  val WorkgroupMember: String = "WorkgroupMember"

  // ---- Project 层 ----
  val ProjectOwner: String = "ProjectOwner"
  val ProjectMember: String = "ProjectMember"

  // ---- Workshop 层 ----
  val Facilitator: String = "Facilitator"
  val DomainExpert: String = "DomainExpert"
  val MetadataEngineer: String = "MetadataEngineer"
  // S2-46 拍板 S-10（2026-09-05·spec §7.1）：知识管理员审阅 MERGE_REQUESTED 段，
  // 批准合入（MergeKnowledgePatch）或退回（DeclineKnowledgeMerge）。rolemap 首次重生成时落位。
  val KnowledgeCurator: String = "KnowledgeCurator"

  // ---- Session 层（S2-87 拍板 2026-09-13：Session＝工作台协作面——细角色只在此范围；
  //      Tenant/Workgroup/Workshop 是生命周期域归 Owner 管理，无协作面） ----
  val Observer: String = "Observer"

  /** Session 协作面六值白名单（AssignSessionRole/AssignWorkshopRole role 合法值域）：
    * Workshop 层四业务角色＋AiAgent＋Observer（与 ObserverInvited 语义对齐）。 */
  object SessionRoles {
    val All: Set[String] = Set(Facilitator, DomainExpert, MetadataEngineer, KnowledgeCurator, AiAgent, Observer)
  }

  /** 白名单全集（对账门禁用） */
  val All: Set[String] = Set(User, SystemOperator, TenantOwner, TenantMember, System, AiAgent,
    WorkgroupOwner, WorkgroupMember, ProjectOwner, ProjectMember, Facilitator, DomainExpert, MetadataEngineer, KnowledgeCurator, Observer)
}

sealed trait RoleMode
object RoleMode {
  /** 迁移期：声明 roles 但会话缺角色信息时告警放行（现状兼容，零破坏上线） */
  case object Migration extends RoleMode
  /** 收尾态：角色不符一律 403 ROLE_REQUIRED（CommandRoleMap 元数据化后切换） */
  case object Enforce extends RoleMode
}

/** 角色门卫配置（application.conf: auth.roleGate.mode = migration | enforce） */
final case class RoleGate(mode: RoleMode)
object RoleGate {
  val migration: RoleGate = RoleGate(RoleMode.Migration)

  def fromConfig(raw: String): RoleGate = raw.trim.toLowerCase match {
    case "enforce" => RoleGate(RoleMode.Enforce)
    case _         => RoleGate(RoleMode.Migration)
  }
}
