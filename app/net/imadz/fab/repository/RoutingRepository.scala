package net.imadz.fab.repository

import net.imadz.fab.model.{ProductRouting, RoutingStep}

/**
 * 工艺路线存储接口。
 *
 * 生产环境实现：JDBC / MongoDB / 配置中心 / MES 集成。
 * 当前提供内存实现用于开发和测试。
 */
trait RoutingRepository {
  def findByProduct(productId: String): ProductRouting
  def findAll: Seq[ProductRouting]
  def save(routing: ProductRouting): Unit
  def updateVersion(productId: String, newSteps: List[RoutingStep]): ProductRouting
}

import scala.collection.mutable

/**
 * 内存工艺路线存储——用于开发和演示。
 */
class InMemoryRoutingRepository extends RoutingRepository {

  private val store: mutable.Map[String, ProductRouting] = mutable.Map.empty

  override def findByProduct(productId: String): ProductRouting =
    store.getOrElse(productId,
      throw new NoSuchElementException(s"Routing not found for product: $productId"))

  override def findAll: Seq[ProductRouting] = store.values.toSeq

  override def save(routing: ProductRouting): Unit = {
    store(routing.productId) = routing
  }

  override def updateVersion(productId: String, newSteps: List[RoutingStep]): ProductRouting = {
    val existing = findByProduct(productId)
    val updated = existing.copy(steps = newSteps, version = existing.version + 1)
    store(productId) = updated
    updated
  }
}

/**
 * 预填充的工艺路线仓库——包含演示数据。
 */
object DemoRoutingRepo {
  import net.imadz.fab.model.EquipmentArea
  import scala.concurrent.duration._

  def create(): InMemoryRoutingRepository = {
    val repo = new InMemoryRoutingRepository

    // 注册示例工艺路线
    repo.save(ProductRouting.exampleRouting)

    // 额外注册一个需要多次重入的工艺路线（如 3D NAND 需要多次沉积+刻蚀循环）
    repo.save(ProductRouting(
      productId = "NAND-96L-A",
      steps = List(
        net.imadz.fab.model.RoutingStep("op-010", EquipmentArea.WetClean,    "CLEAN-INIT-001",    30.minutes),
        net.imadz.fab.model.RoutingStep("op-020", EquipmentArea.Diffusion,   "DIFF-TUNNEL-001",   60.minutes),
        net.imadz.fab.model.RoutingStep("op-030", EquipmentArea.Deposition,  "DEP-ONO-001",       50.minutes),
        net.imadz.fab.model.RoutingStep("op-040", EquipmentArea.Etch,        "ETCH-CH-001",       40.minutes),
        // 重入循环 ×3：沉积 → 刻蚀
        net.imadz.fab.model.RoutingStep("op-050", EquipmentArea.Deposition,  "DEP-WL-002",        50.minutes),
        net.imadz.fab.model.RoutingStep("op-060", EquipmentArea.Etch,        "ETCH-CH-002",       40.minutes),
        net.imadz.fab.model.RoutingStep("op-070", EquipmentArea.Deposition,  "DEP-WL-003",        50.minutes),
        net.imadz.fab.model.RoutingStep("op-080", EquipmentArea.Etch,        "ETCH-CH-003",       40.minutes),
        net.imadz.fab.model.RoutingStep("op-090", EquipmentArea.Deposition,  "DEP-WL-004",        50.minutes),
        net.imadz.fab.model.RoutingStep("op-100", EquipmentArea.Etch,        "ETCH-CH-004",       40.minutes),
        net.imadz.fab.model.RoutingStep("op-110", EquipmentArea.CMP,         "CMP-PLANAR-001",    30.minutes),
        net.imadz.fab.model.RoutingStep("op-120", EquipmentArea.Metrology,   "MET-FINAL-001",     25.minutes),
        net.imadz.fab.model.RoutingStep("op-130", EquipmentArea.Logistics,   "LOG-WIP-001",       10.minutes)
      ),
      version = 1,
      mergeBeforeWarehouse = true
    ))

    repo
  }
}
