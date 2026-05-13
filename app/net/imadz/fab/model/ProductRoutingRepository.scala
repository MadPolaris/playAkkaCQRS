package net.imadz.fab.model

/**
 * In-memory repository for ProductRouting lookup.
 *
 * In production, this would query a POR (Process of Record) database.
 * For the M3.5 demo, routes are pre-registered and looked up by product ID.
 */
object ProductRoutingRepository {

  private var routings: Map[String, ProductRouting] = Map(
    ProductRouting.exampleRouting.productId -> ProductRouting.exampleRouting
  )

  def findByProductId(productId: String): Option[ProductRouting] =
    routings.get(productId)

  def register(routing: ProductRouting): Unit = {
    routings += routing.productId -> routing
  }

  def listProducts: Seq[ProductRouting] =
    routings.values.toSeq.sortBy(_.productId)
}
