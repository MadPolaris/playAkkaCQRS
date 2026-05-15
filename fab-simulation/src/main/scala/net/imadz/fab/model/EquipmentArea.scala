package net.imadz.fab.model

/**
 * 半导体 Fab 设备区——9 个工艺区 + 物流指挥系统 = 10 个外部系统。
 *
 * 每个设备区对应一套 Pipeline + ReBatchPolicy + PhysicalConstraints，
 * 但共享同一套 Connector 组件（HTTP/SFTP/断路器/重试）。
 *
 * 工件（Wafer/Lot）可能多次重入同一设备区——`reentryIndex` 追踪第几次访问。
 */
sealed trait EquipmentArea {
  def areaId: String
  def displayName: String
}

object EquipmentArea {

  case object WetClean extends EquipmentArea {
    val areaId = "CLEAN"; val displayName = "湿法清洗"
  }
  case object Diffusion extends EquipmentArea {
    val areaId = "DIFF"; val displayName = "扩散/氧化"
  }
  case object Lithography extends EquipmentArea {
    val areaId = "LITHO"; val displayName = "光刻"
  }
  case object Etch extends EquipmentArea {
    val areaId = "ETCH"; val displayName = "刻蚀"
  }
  case object Implant extends EquipmentArea {
    val areaId = "IMPL"; val displayName = "离子注入"
  }
  case object Deposition extends EquipmentArea {
    val areaId = "DEP"; val displayName = "薄膜沉积"
  }
  case object CMP extends EquipmentArea {
    val areaId = "CMP"; val displayName = "化学机械抛光"
  }
  case object Metrology extends EquipmentArea {
    val areaId = "MET"; val displayName = "量测"
  }
  case object Drying extends EquipmentArea {
    val areaId = "DRY"; val displayName = "干燥"
  }
  case object Logistics extends EquipmentArea {
    val areaId = "LOG"; val displayName = "物流指挥"
  }

  /** 全部 10 个设备区 */
  val all: Seq[EquipmentArea] = Seq(
    WetClean, Diffusion, Lithography, Etch, Implant,
    Deposition, CMP, Metrology, Drying, Logistics
  )

  /** 按 areaId 查找 */
  def byId(id: String): Option[EquipmentArea] =
    all.find(_.areaId == id)

  /**
   * 计算重入索引——同一 Lot 第几次访问此设备区。
   *
   * 例如：Lithography → Etch → Lithography → Deposition → Lithography
   *       则 Lithography 被访问 3 次，reentryIndex 分别为 0, 1, 2
   */
  def reentryIndex(area: EquipmentArea, visitedSteps: Seq[String]): Int =
    visitedSteps.count(_ == area.areaId)
}
